package org.egothor.methodatlas.discovery.typescript;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A single long-lived Node.js worker process that scans TypeScript files on
 * demand.
 *
 * <p>
 * Each {@code TypeScriptWorker} owns exactly one Node.js child process.  The
 * process stays alive across multiple scan requests; it is replaced only when
 * it exits unexpectedly or produces an invalid response.  A fresh worker is
 * created by {@link TypeScriptWorkerPool} after each restart event.
 * </p>
 *
 * <h2>Protocol</h2>
 *
 * <p>
 * Requests are written to the worker's {@code stdin} as a single JSON line:
 * </p>
 * <pre>
 * {"requestId":"&lt;uuid&gt;","filePath":"&lt;abs-path&gt;","functionNames":["test","it"]}
 * </pre>
 *
 * <p>
 * Responses are read from {@code stdout} as a single JSON line per request:
 * </p>
 * <pre>
 * {"requestId":"&lt;uuid&gt;","methods":[...],"error":null}
 * </pre>
 *
 * <p>
 * The {@code stderr} of the worker process is read asynchronously and logged
 * at {@code FINE} level so that Node.js warnings do not block the scan.
 * </p>
 *
 * <h2>Timeout enforcement</h2>
 *
 * <p>
 * If the worker does not produce a response line within
 * {@code timeoutMillis}, the worker process is forcibly killed and a
 * {@link WorkerException} is thrown.  The killing decision is logged with the
 * request ID, file path, and elapsed time so audit teams can identify
 * problematic source files.
 * </p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * Instances are not thread-safe.  Each instance must be used by at most one
 * thread at a time.  {@link TypeScriptWorkerPool} ensures this through its
 * worker-borrowing protocol.
 * </p>
 */
final class TypeScriptWorker {

    private static final Logger LOG = Logger.getLogger(TypeScriptWorker.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path bundlePath;
    private final NodeEnvironment nodeEnv;
    private final long timeoutMillis;
    private final int workerIndex;

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    @SuppressWarnings("PMD.DoNotUseThreads")
    private Thread stderrDrainer;
    private long pid = -1;

    /**
     * Creates a worker descriptor.  The actual Node.js process is not started
     * until {@link #start()} is called.
     *
     * @param bundlePath     path to the verified, extracted bundle JS file
     * @param nodeEnv        Node.js environment information
     * @param timeoutMillis  per-request timeout in milliseconds
     * @param workerIndex    zero-based index within the pool (used in log messages)
     */
    /* default */ TypeScriptWorker(Path bundlePath, NodeEnvironment nodeEnv, long timeoutMillis, int workerIndex) {
        this.bundlePath = bundlePath;
        this.nodeEnv = nodeEnv;
        this.timeoutMillis = timeoutMillis;
        this.workerIndex = workerIndex;
    }

    /**
     * Starts the Node.js worker process.
     *
     * <p>
     * Constructs the command line (with optional filesystem permission flags
     * when Node.js 20 is detected), starts the process, and launches an
     * async stderr-draining thread.
     * </p>
     *
     * @throws IOException if the process cannot be started
     */
    /* default */ void start() throws IOException {
        List<String> cmd = buildCommand(null);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false); // keep stderr separate so we can drain it

        process = pb.start();
        pid = process.pid();
        stdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        stderrDrainer = startStderrDrainer(process, workerIndex, pid);

        if (LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO,
                    "TypeScript scanner worker[{0}] started — node={1}, pid={2}, bundle={3}",
                    new Object[] { workerIndex, nodeEnv.versionString(), pid, bundlePath });
        }
    }

    /**
     * Starts the Node.js worker process with the given root path for
     * file-system sandboxing.
     *
     * <p>
     * When Node.js 20 or later is detected the process is started with
     * {@code --experimental-permission --allow-fs-read=<root>} to restrict
     * the worker to reading only files under {@code allowedRoot}.
     * </p>
     *
     * @param allowedRoot scan root path used for permission sandboxing;
     *                    {@code null} means no restriction beyond the default
     * @throws IOException if the process cannot be started
     */
    /* default */ void start(Path allowedRoot) throws IOException {
        List<String> cmd = buildCommand(allowedRoot);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);

        process = pb.start();
        pid = process.pid();
        stdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        stderrDrainer = startStderrDrainer(process, workerIndex, pid);

        if (LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO,
                    "TypeScript scanner worker[{0}] started — node={1}, pid={2}, "
                    + "bundle={3}, allowed-root={4}",
                    new Object[] { workerIndex, nodeEnv.versionString(), pid,
                            bundlePath, allowedRoot != null ? allowedRoot : "unrestricted" });
        }
    }

    /**
     * Sends a scan request to the worker and waits for the response.
     *
     * @param filePath      absolute path to the TypeScript file to scan
     * @param functionNames function-call names that identify test methods
     * @return list of raw method descriptors from the worker response
     * @throws WorkerException if the worker does not respond within the
     *         configured timeout, returns an error response, or the
     *         underlying process has died
     * @throws IOException if writing to stdin or reading from stdout fails
     */
    /* default */ List<MethodDescriptor> scan(Path filePath, List<String> functionNames)
            throws IOException, WorkerException {
        if (process == null || !process.isAlive()) {
            throw new WorkerException("Worker process is not alive");
        }

        String requestId = UUID.randomUUID().toString();
        String requestLine = buildRequestLine(requestId, filePath, functionNames);

        stdin.write(requestLine);
        stdin.newLine();
        stdin.flush();

        String responseLine = readWithTimeout(filePath, requestId);

        return parseResponse(responseLine, requestId, filePath);
    }

    /**
     * Returns {@code true} when the underlying Node.js process is alive.
     *
     * @return {@code true} if the worker can process requests
     */
    /* default */ boolean isAlive() {
        return process != null && process.isAlive();
    }

    /**
     * Returns the OS process ID of the worker, or {@code -1} if not started.
     *
     * @return OS PID
     */
    /* default */ long pid() {
        return pid;
    }

    /**
     * Kills the worker process and interrupts the stderr-drainer thread.
     *
     * <p>
     * This method is idempotent: calling it on an already-dead process is
     * safe.  The kill decision is logged at {@code INFO} level.
     * </p>
     *
     * @param reason human-readable reason for the kill (included in the log)
     */
    /* default */ void kill(String reason) {
        if (process != null) {
            if (LOG.isLoggable(Level.INFO)) {
                LOG.log(Level.INFO,
                        "TypeScript scanner worker[{0}] killed — pid={1}, reason={2}",
                        new Object[] { workerIndex, pid, reason });
            }
            process.destroyForcibly();
        }
        if (stderrDrainer != null) {
            stderrDrainer.interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the Node.js command line.  When Node.js 20 or later is detected
     * and {@code allowedRoot} is not {@code null}, filesystem permission flags
     * are prepended.
     *
     * <h4>Permission model compatibility</h4>
     * <p>Node.js&nbsp;20–21 used {@code --experimental-permission}; Node.js&nbsp;22
     * and later uses the stable {@code --permission} flag.  The correct name is
     * returned by {@link NodeEnvironment#permissionFlagName()}.</p>
     *
     * <p>Each allowed path is passed as a separate {@code --allow-fs-read}
     * argument rather than comma-separated values, because the comma-separated
     * form was not reliably supported across all Node.js versions.</p>
     *
     * <p>The scan root is suffixed with {@code /**} so that Node.js grants
     * recursive read access to all files beneath it.  Without this glob,
     * Node.js&nbsp;22+ treats a bare directory path as permission to stat the
     * directory itself only — not its contents.</p>
     *
     * <p>All paths in {@code --allow-fs-read} use forward slashes regardless
     * of the host OS, because the Node.js permission model on Windows requires
     * consistent path separators for reliable prefix matching.</p>
     *
     * @param allowedRoot  scan root; {@code null} means no sandboxing
     * @return command-line token list
     */
    private List<String> buildCommand(Path allowedRoot) {
        List<String> cmd = new ArrayList<>();
        cmd.add("node");
        if (nodeEnv.isPermissionModelSupported() && allowedRoot != null) {
            cmd.add(nodeEnv.permissionFlagName());
            // Use separate --allow-fs-read flags (comma-separated form is unreliable
            // across Node.js versions). Convert backslashes to forward slashes so
            // the permission model's path matching works correctly on Windows.
            // Append /** to the scan root so recursive access is granted (Node.js v22+
            // requires explicit glob or trailing slash for directory recursion).
            String bundleStr = bundlePath.toAbsolutePath().toString().replace('\\', '/');
            String rootStr   = allowedRoot.toAbsolutePath().toString().replace('\\', '/');
            cmd.add("--allow-fs-read=" + bundleStr);
            cmd.add("--allow-fs-read=" + rootStr + "/**");
        }
        cmd.add(bundlePath.toAbsolutePath().toString());
        return cmd;
    }

    /**
     * Serialises a scan request as a single JSON line.
     *
     * @param requestId     unique request identifier
     * @param filePath      file to scan
     * @param functionNames test-function names
     * @return JSON line (no trailing newline)
     * @throws IOException if JSON serialisation fails (should never happen)
     */
    private static String buildRequestLine(String requestId, Path filePath,
            List<String> functionNames) throws IOException {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("requestId", requestId);
        req.put("filePath", filePath.toAbsolutePath().toString());
        ArrayNode fns = req.putArray("functionNames");
        for (String fn : functionNames) {
            fns.add(fn);
        }
        return MAPPER.writeValueAsString(req);
    }

    /**
     * Reads one response line from the worker's stdout, waiting at most
     * {@link #timeoutMillis} milliseconds.
     *
     * <p>
     * Because {@link BufferedReader#readLine()} is blocking, a separate
     * reader thread is used so that a timeout can be enforced with
     * {@link Thread#join(long)}.
     * </p>
     *
     * @param filePath  file being scanned (for log messages)
     * @param requestId request ID (for log messages)
     * @return the response JSON line
     * @throws WorkerException  if the timeout elapses or the stream closes
     *                          unexpectedly
     * @throws IOException      if the reader thread itself throws
     */
    @SuppressWarnings("PMD.DoNotUseThreads")
    private String readWithTimeout(Path filePath, String requestId)
            throws IOException, WorkerException {
        // Shared result container accessed from both this thread and the reader thread.
        final String[] result = { null };
        final IOException[] ioError = { null };

        Thread reader = new Thread(() -> {
            try {
                result[0] = stdout.readLine();
            } catch (IOException e) {
                ioError[0] = e;
            }
        }, "ts-worker-reader-" + workerIndex);
        reader.setDaemon(true);
        reader.start();

        try {
            reader.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reader.interrupt();
            throw new WorkerException("Interrupted while waiting for worker response", e);
        }

        if (reader.isAlive()) {
            // Timeout elapsed — kill the worker and the reader thread.
            reader.interrupt();
            kill("per-file timeout of " + timeoutMillis + " ms exceeded for " + filePath
                    + " (requestId=" + requestId + ")");
            throw new WorkerException("Worker timeout after " + timeoutMillis
                    + " ms scanning " + filePath);
        }

        if (ioError[0] != null) {
            throw ioError[0];
        }

        if (result[0] == null) {
            throw new WorkerException(
                    "Worker stdout closed unexpectedly while scanning " + filePath);
        }

        return result[0];
    }

    /**
     * Parses a worker response JSON line into a list of method descriptors.
     *
     * @param responseLine  raw JSON line from the worker
     * @param requestId     expected request ID (validated against the response)
     * @param filePath      file being scanned (for error messages)
     * @return list of method descriptors; never {@code null}
     * @throws WorkerException if the response contains an error or the request
     *         ID does not match
     * @throws IOException if JSON parsing fails
     */
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private static List<MethodDescriptor> parseResponse(String responseLine, String requestId,
            Path filePath) throws IOException, WorkerException {
        JsonNode root = MAPPER.readTree(responseLine);

        // Validate request ID to catch out-of-sync responses (protocol error).
        String responseId = root.path("requestId").asText(null);
        if (!requestId.equals(responseId)) {
            throw new WorkerException(
                    "Response requestId mismatch: expected=" + requestId
                    + ", got=" + responseId + " for file " + filePath);
        }

        // Surface worker-side errors.
        JsonNode errorNode = root.path("error");
        if (!errorNode.isNull() && !errorNode.isMissingNode()) {
            String errorMsg = errorNode.asText();
            if (errorMsg != null && !errorMsg.isBlank()) {
                throw new WorkerException("Worker reported error scanning " + filePath
                        + ": " + errorMsg);
            }
        }

        // Parse the methods array.
        List<MethodDescriptor> methods = new ArrayList<>();
        JsonNode methodsNode = root.path("methods");
        if (methodsNode.isArray()) {
            for (JsonNode m : methodsNode) {
                String name = m.path("name").asText("<anonymous>");
                List<String> describe = null;
                JsonNode descNode = m.path("describe");
                if (descNode.isArray() && !descNode.isEmpty()) {
                    describe = new ArrayList<>();
                    for (JsonNode d : descNode) {
                        describe.add(d.asText());
                    }
                }
                int beginLine = m.path("beginLine").asInt(0);
                int endLine   = m.path("endLine").asInt(0);
                int loc       = m.path("loc").asInt(1);
                methods.add(new MethodDescriptor(name, describe, beginLine, endLine, loc));
            }
        }
        return methods;
    }

    /**
     * Starts a daemon thread that drains the worker's stderr stream and logs
     * each line at {@code FINE} level.
     *
     * @param proc         worker process
     * @param workerIndex  worker index (for log messages)
     * @param processId    OS PID (for log messages)
     * @return the started drainer thread
     */
    @SuppressWarnings("PMD.DoNotUseThreads")
    private static Thread startStderrDrainer(Process proc, int workerIndex, long processId) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.fine("worker[" + workerIndex + "](pid=" + processId + ") stderr: " + line);
                    }
                }
            } catch (IOException e) {
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, "stderr drainer for worker[" + workerIndex + "] closed", e);
                }
            }
        }, "ts-worker-stderr-" + workerIndex);
        t.setDaemon(true);
        t.start();
        return t;
    }

    // -------------------------------------------------------------------------
    // Nested types
    // -------------------------------------------------------------------------

    /**
     * Immutable data transfer object carrying the raw scan result for a single
     * test method as reported by the Node.js worker.
     *
     * @param name       test name extracted from the first argument of the
     *                   test-function call
     * @param describe   ordered list of enclosing describe-block names, or
     *                   {@code null} when the test is at the top level
     * @param beginLine  1-based line number of the call expression start
     * @param endLine    1-based line number of the call expression end
     * @param loc        inclusive line count (at least 1)
     */
    /* default */ record MethodDescriptor(
            String name,
            List<String> describe,
            int beginLine,
            int endLine,
            int loc) {}

    /**
     * Signals that the worker process failed to produce a valid response.
     * The caller ({@link TypeScriptWorkerPool}) catches this exception, kills
     * and replaces the worker, and records the restart in the circuit breaker.
     */
    /* default */ static final class WorkerException extends Exception {
        private static final long serialVersionUID = 1L;

        /* default */ WorkerException(String message) {
            super(message);
        }

        /* default */ WorkerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
