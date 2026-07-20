package org.egothor.methodatlas.discovery.python;

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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A single long-lived Python worker process that scans Python test files on
 * demand.
 *
 * <p>
 * Each {@code PythonWorker} owns exactly one Python child process running
 * {@code py-scanner.py}.  The process stays alive across multiple scan
 * requests; it is replaced only when it exits unexpectedly or produces an
 * invalid response.  A fresh worker is created by {@link PythonWorkerPool}
 * after each restart event.
 * </p>
 *
 * <h2>Protocol</h2>
 *
 * <p>
 * Requests are written to the worker's {@code stdin} as a single JSON line:
 * </p>
 * <pre>
 * {"requestId":"&lt;uuid&gt;","filePath":"&lt;abs-path&gt;"}
 * </pre>
 *
 * <p>
 * Responses are read from {@code stdout} as a single JSON line per request:
 * </p>
 * <pre>
 * {"requestId":"&lt;uuid&gt;","methods":[...],"error":null}
 * </pre>
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * Instances are not thread-safe.  Each instance must be used by at most one
 * thread at a time.  {@link PythonWorkerPool} ensures this through its
 * worker-borrowing protocol.
 * </p>
 */
// A worker manages an OS subprocess plus its reader/stderr threads; the J2EE
// "no threads" rule (PMD.DoNotUseThreads) does not apply to this CLI tool.
@SuppressWarnings("PMD.DoNotUseThreads")
final class PythonWorker {

    private static final Logger LOG = Logger.getLogger(PythonWorker.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path scriptPath;
    private final PythonEnvironment pythonEnv;
    private final long timeoutMillis;
    private final int workerIndex;

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private Thread stderrDrainer;
    /** One reusable reader thread per worker, replacing the old thread-per-scan. */
    private ExecutorService readerExecutor;
    private long pid = -1;

    /**
     * Creates a worker descriptor.  The actual Python process is not started
     * until {@link #start()} is called.
     *
     * @param scriptPath     path to the extracted {@code py-scanner.py} script
     * @param pythonEnv      Python environment information
     * @param timeoutMillis  per-request timeout in milliseconds
     * @param workerIndex    zero-based index within the pool
     */
    /* default */ PythonWorker(Path scriptPath, PythonEnvironment pythonEnv,
            long timeoutMillis, int workerIndex) {
        this.scriptPath = scriptPath;
        this.pythonEnv = pythonEnv;
        this.timeoutMillis = timeoutMillis;
        this.workerIndex = workerIndex;
    }

    /**
     * Starts the Python worker process.
     *
     * @throws IOException if the process cannot be started
     */
    /* default */ void start() throws IOException {
        List<String> cmd = List.of(pythonEnv.executableName(),
                scriptPath.toAbsolutePath().toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);

        process = pb.start();
        pid = process.pid();
        stdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        stderrDrainer = startStderrDrainer(process, workerIndex, pid);
        readerExecutor = newReaderExecutor(workerIndex);

        if (LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO,
                    "Python scanner worker[{0}] started — python={1}, pid={2}, script={3}",
                    new Object[] { workerIndex, pythonEnv.versionString(), pid, scriptPath });
        }
    }

    /**
     * Sends a scan request to the worker and waits for the response.
     *
     * @param filePath absolute path to the Python file to scan
     * @return list of raw method descriptors from the worker response
     * @throws WorkerException if the worker does not respond, returns an error,
     *                         or the process has died
     * @throws IOException     if writing to stdin or reading from stdout fails
     */
    /* default */ List<MethodDescriptor> scan(Path filePath)
            throws IOException, WorkerException {
        if (process == null || !process.isAlive()) {
            throw new WorkerException("Worker process is not alive");
        }

        String requestId = UUID.randomUUID().toString();
        ObjectNode req = MAPPER.createObjectNode();
        req.put("requestId", requestId);
        req.put("filePath", filePath.toAbsolutePath().toString());
        String requestLine = MAPPER.writeValueAsString(req);

        stdin.write(requestLine);
        stdin.newLine();
        stdin.flush();

        String responseLine = readWithTimeout(filePath, requestId);
        return parseResponse(responseLine, requestId, filePath);
    }

    /**
     * Returns {@code true} when the underlying Python process is alive.
     *
     * @return {@code true} if the worker can process requests
     */
    /* default */ boolean isAlive() {
        return process != null && process.isAlive();
    }

    /**
     * Kills the worker process and interrupts the stderr-drainer thread.
     * Idempotent.
     *
     * @param reason human-readable reason for the kill
     */
    /* default */ void kill(String reason) {
        if (process != null) {
            if (LOG.isLoggable(Level.INFO)) {
                LOG.log(Level.INFO,
                        "Python scanner worker[{0}] killed — pid={1}, reason={2}",
                        new Object[] { workerIndex, pid, reason });
            }
            process.destroyForcibly();
        }
        if (stderrDrainer != null) {
            stderrDrainer.interrupt();
        }
        if (readerExecutor != null) {
            readerExecutor.shutdownNow();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────

    // The ExecutionException is unwrapped so a genuine IOException keeps its type
    // for the pool's hard-I/O handling; that deliberate unwrap is what PMD flags
    // as PreserveStackTrace.
    @SuppressWarnings("PMD.PreserveStackTrace")
    private String readWithTimeout(Path filePath, String requestId)
            throws IOException, WorkerException {
        Future<String> future = readerExecutor.submit(stdout::readLine);

        String line;
        try {
            line = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            kill("per-file timeout of " + timeoutMillis + " ms exceeded for " + filePath
                    + " (requestId=" + requestId + ")");
            throw new WorkerException("Worker timeout after " + timeoutMillis
                    + " ms scanning " + filePath, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new WorkerException("Interrupted while waiting for worker response", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new WorkerException("Worker read failed scanning " + filePath, e);
        }

        if (line == null) {
            throw new WorkerException(
                    "Worker stdout closed unexpectedly while scanning " + filePath);
        }

        return line;
    }

    private static ExecutorService newReaderExecutor(int workerIndex) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "py-worker-reader-" + workerIndex);
            t.setDaemon(true);
            return t;
        });
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private static List<MethodDescriptor> parseResponse(String responseLine, String requestId,
            Path filePath) throws IOException, WorkerException {
        JsonNode root = MAPPER.readTree(responseLine);

        String responseId = root.path("requestId").asString(null);
        if (!requestId.equals(responseId)) {
            throw new WorkerException(
                    "Response requestId mismatch: expected=" + requestId
                    + ", got=" + responseId + " for file " + filePath);
        }

        JsonNode errorNode = root.path("error");
        if (!errorNode.isNull() && !errorNode.isMissingNode()) {
            String errorMsg = errorNode.asString();
            if (errorMsg != null && !errorMsg.isBlank()) {
                throw new WorkerException("Worker reported error scanning " + filePath
                        + ": " + errorMsg);
            }
        }

        List<MethodDescriptor> methods = new ArrayList<>();
        JsonNode methodsNode = root.path("methods");
        if (methodsNode.isArray()) {
            for (JsonNode m : methodsNode) {
                String name = m.path("name").asString("<anonymous>");
                String className = null;
                JsonNode classNode = m.path("className");
                if (!classNode.isNull() && !classNode.isMissingNode()) {
                    className = classNode.asString(null);
                }
                int beginLine = m.path("beginLine").asInt(0);
                int endLine   = m.path("endLine").asInt(0);
                int loc       = m.path("loc").asInt(1);
                List<String> tags = new ArrayList<>();
                JsonNode tagsNode = m.path("tags");
                if (tagsNode.isArray()) {
                    for (JsonNode t : tagsNode) {
                        tags.add(t.asString());
                    }
                }
                methods.add(new MethodDescriptor(name, className, beginLine, endLine, loc,
                        List.copyOf(tags)));
            }
        }
        return methods;
    }

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
        }, "py-worker-stderr-" + workerIndex);
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ── Nested types ──────────────────────────────────────────────────

    /**
     * Immutable data transfer object carrying the raw scan result for a single
     * test method as reported by the Python worker.
     *
     * @param name      test function name (e.g. {@code test_login_valid})
     * @param className enclosing class name, or {@code null} for module-level functions
     * @param beginLine 1-based line number of the {@code def} or {@code async def} keyword
     * @param endLine   1-based line number of the last line of the function body
     * @param loc       inclusive line count (at least 1)
     * @param tags      pytest.mark decorator names; may be empty
     */
    /* default */ record MethodDescriptor(
            String name,
            String className,
            int beginLine,
            int endLine,
            int loc,
            List<String> tags) {}

    /**
     * Signals that the worker process failed to produce a valid response.
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
