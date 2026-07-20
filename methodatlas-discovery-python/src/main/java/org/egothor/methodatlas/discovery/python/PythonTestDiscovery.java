package org.egothor.methodatlas.discovery.python;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.SourceContent;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.util.ConfigProperties;
import org.egothor.methodatlas.util.WorkerCircuitBreaker;

/**
 * Discovers Python test functions and methods in pytest-convention source files.
 *
 * <p>
 * Scans a directory root for Python test files and delegates AST parsing to a
 * pool of long-lived Python worker processes running the bundled
 * {@code py-scanner.py} script.  This eliminates the Python interpreter
 * startup overhead for large codebases.
 * </p>
 *
 * <h2>File selection</h2>
 *
 * <p>
 * Two pytest file-naming conventions are supported by default:
 * </p>
 * <ul>
 *   <li>Files whose name starts with {@code "test_"} and ends with
 *       {@code ".py"} (e.g. {@code test_auth.py}).</li>
 *   <li>Files whose name ends with {@code "_test.py"}
 *       (e.g. {@code security_test.py}).</li>
 * </ul>
 *
 * <p>
 * Additional suffixes may be supplied via
 * {@link TestDiscoveryConfig#fileSuffixesFor(String) fileSuffixesFor("python")}.
 * The {@code test_} prefix check is always active regardless of configured suffixes.
 * </p>
 *
 * <h2>Parsing</h2>
 *
 * <p>
 * Parsing is performed by the bundled Python {@code py-scanner.py} script using
 * the standard-library {@code ast} module (Python 3.8+).  The script resolves
 * all Python syntax correctly and extracts:
 * </p>
 * <ul>
 *   <li>test functions ({@code def test_*}) — both sync and async</li>
 *   <li>test methods inside {@code Test*} classes</li>
 *   <li>{@code @pytest.mark.*} decorator names as tags</li>
 *   <li>exact begin/end line numbers and LOC from the AST</li>
 * </ul>
 *
 * <h2>Python availability</h2>
 *
 * <p>
 * Python is detected lazily on the first call to {@link #discover(Path)} that
 * finds matching files.  When Python 3.8+ is absent, a {@code WARNING} is
 * logged and an empty stream is returned; {@link #hadErrors()} returns
 * {@code true}.
 * </p>
 *
 * <h2>Resource management</h2>
 *
 * <p>
 * The worker pool holds live Python sub-processes.  The orchestration layer
 * calls {@link #close()} when the scan run finishes.  A JVM shutdown hook
 * registered by the pool acts as a backstop.
 * </p>
 *
 * <h2>ServiceLoader registration</h2>
 *
 * <p>
 * This class is registered in
 * {@code META-INF/services/org.egothor.methodatlas.api.TestDiscovery} so that
 * it is loaded automatically via {@link java.util.ServiceLoader}.
 * </p>
 *
 * @see PythonWorkerPool
 * @see PythonEnvironment
 * @see TestDiscovery
 * @see TestDiscoveryConfig
 */
public final class PythonTestDiscovery implements TestDiscovery {

    private static final Logger LOG =
            Logger.getLogger(PythonTestDiscovery.class.getName());

    /** Default file suffixes when no configuration is supplied. */
    private static final List<String> DEFAULT_SUFFIXES = List.of("_test.py");

    /** Default pool size: at most 2 Python processes to keep memory use modest. */
    private static final int DEFAULT_POOL_SIZE =
            Math.min(2, Runtime.getRuntime().availableProcessors());

    /** Default per-file worker timeout in seconds. */
    private static final int DEFAULT_TIMEOUT_SEC = 30;

    /** Default circuit-breaker: 5 restarts within 60 seconds trips the circuit. */
    private static final int DEFAULT_MAX_RESTARTS = 5;
    private static final int DEFAULT_RESTART_WINDOW_SEC = 60;

    // ── Configured state (set by configure()) ──────────────────────────────
    private List<String> fileSuffixes = DEFAULT_SUFFIXES;
    private int poolSize = DEFAULT_POOL_SIZE;
    private long workerTimeoutMillis = DEFAULT_TIMEOUT_SEC * 1_000L;
    private int maxRestarts = DEFAULT_MAX_RESTARTS;
    private int restartWindowSec = DEFAULT_RESTART_WINDOW_SEC;

    // ── Runtime state (initialised lazily on first discover()) ────────────
    private final ReentrantLock poolInitLock = new ReentrantLock();
    private PythonEnvironment pythonEnv;
    private PythonWorkerPool workerPool;
    private final AtomicBoolean errors = new AtomicBoolean();

    /**
     * No-arg constructor required by {@link java.util.ServiceLoader}.
     */
    public PythonTestDiscovery() {
        // Required by ServiceLoader
    }

    /**
     * Returns the unique identifier of this discovery provider: {@code "python"}.
     *
     * @return {@code "python"}
     */
    @Override
    public String pluginId() {
        return "python";
    }

    /**
     * Configures this provider from a {@link TestDiscoveryConfig}.
     *
     * <p>
     * Reads the following configuration knobs:
     * </p>
     * <ul>
     * <li><b>File suffixes</b> — via {@link TestDiscoveryConfig#fileSuffixesFor}
     *     with ID {@code "python"}.  Falls back to {@code _test.py}.</li>
     * <li><b>{@code python.poolSize}</b> — number of worker processes;
     *     default: {@code Math.min(2, availableProcessors())}.</li>
     * <li><b>{@code python.workerTimeoutSec}</b> — per-file timeout;
     *     default: {@value #DEFAULT_TIMEOUT_SEC} s.</li>
     * <li><b>{@code python.maxConsecutiveRestarts}</b> — circuit-breaker
     *     restart limit; default: {@value #DEFAULT_MAX_RESTARTS}.</li>
     * <li><b>{@code python.restartWindowSec}</b> — circuit-breaker sliding
     *     window; default: {@value #DEFAULT_RESTART_WINDOW_SEC} s.</li>
     * </ul>
     *
     * <p>
     * Python detection and worker-pool creation are deferred until the first
     * call to {@link #discover(Path)} that actually finds a matching file.
     * </p>
     *
     * @param config runtime configuration; never {@code null}
     */
    @Override
    public void configure(TestDiscoveryConfig config) {
        List<String> suffixes = config.fileSuffixesFor(pluginId());
        this.fileSuffixes = suffixes.isEmpty() ? DEFAULT_SUFFIXES : suffixes;

        this.poolSize = ConfigProperties.parseInt(config, "python.poolSize", DEFAULT_POOL_SIZE);
        int timeoutSec = ConfigProperties.parseInt(config, "python.workerTimeoutSec", DEFAULT_TIMEOUT_SEC);
        this.workerTimeoutMillis = timeoutSec * 1_000L;
        this.maxRestarts = ConfigProperties.parseInt(config, "python.maxConsecutiveRestarts",
                DEFAULT_MAX_RESTARTS);
        this.restartWindowSec = ConfigProperties.parseInt(config, "python.restartWindowSec",
                DEFAULT_RESTART_WINDOW_SEC);
    }

    /**
     * Scans {@code root} and returns a stream of all discovered Python test
     * methods.
     *
     * <p>
     * The file tree is traversed first.  Python detection and worker-pool
     * creation are deferred until at least one matching file is found; projects
     * with no Python test files never start a Python process at all.  If
     * matching files are found but Python is unavailable, a warning is logged,
     * {@link #hadErrors()} returns {@code true}, and an empty stream is
     * returned.
     * </p>
     *
     * @param root directory to scan; must be an existing directory
     * @return stream of discovered test methods; never {@code null}
     * @throws IOException if traversing the file tree fails
     */
    @Override
    public Stream<DiscoveredMethod> discover(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return Stream.empty();
        }

        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        Path fn = p.getFileName();
                        return fn != null && isPythonTestFile(fn.toString(), fileSuffixes);
                    })
                    .toList();
        }

        if (files.isEmpty()) {
            return Stream.empty();
        }

        ensurePoolReady();

        if (pythonEnv == null || !pythonEnv.isAvailable() || workerPool == null) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING,
                        "Python 3.8+ is unavailable — {0} Python test file(s) under {1} will not be scanned.",
                        new Object[] { files.size(), root });
            }
            errors.set(true);
            return Stream.empty();
        }

        List<DiscoveredMethod> result = new ArrayList<>();
        for (Path file : files) {
            processFile(root, file, result);
        }
        return result.stream();
    }

    /** {@inheritDoc} */
    @Override
    public boolean hadErrors() {
        return errors.get();
    }

    /**
     * Shuts down the worker pool and removes the JVM shutdown hook registered
     * by the pool.  Idempotent.
     *
     * @throws IOException never thrown; declared to satisfy {@link java.io.Closeable}
     */
    @Override
    public void close() throws IOException {
        if (workerPool != null) {
            workerPool.close();
            workerPool = null;
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Initialises Python detection and the worker pool on first use.
     * Subsequent calls are no-ops.
     */
    private void ensurePoolReady() {
        poolInitLock.lock();
        try {
            if (pythonEnv != null) {
                return;
            }
            pythonEnv = new PythonEnvironment();
            if (!pythonEnv.isAvailable()) {
                return;
            }
            try {
                Path scriptPath = PythonScriptExtractor.extractScript();
                WorkerCircuitBreaker cb =
                        new WorkerCircuitBreaker("Python", "python", maxRestarts, restartWindowSec);
                workerPool = new PythonWorkerPool(
                        scriptPath, pythonEnv, poolSize, workerTimeoutMillis, cb);
            } catch (IOException e) {
                LOG.log(Level.SEVERE,
                        "Failed to initialise Python worker pool — Python scanning disabled", e);
                pythonEnv = null;
            }
        } finally {
            poolInitLock.unlock();
        }
    }

    /**
     * Sends a single file to the worker pool, converts the response into
     * {@link DiscoveredMethod} records, and appends them to {@code result}.
     *
     * @param root    scan root (for module-path computation)
     * @param file    absolute path to the source file
     * @param result  accumulator for discovered methods
     */
    private void processFile(Path root, Path file, List<DiscoveredMethod> result) {
        List<PythonWorker.MethodDescriptor> descriptors;
        try {
            descriptors = workerPool.scan(file);
        } catch (IOException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Cannot scan Python file: " + file, e);
            }
            errors.set(true);
            return;
        }

        if (descriptors.isEmpty()) {
            return;
        }

        String modulePath = buildModulePath(file, root);
        SourceContent sourceContent = SourceContent.ofFile(file);

        for (PythonWorker.MethodDescriptor d : descriptors) {
            String fqcn = d.className() != null
                    ? modulePath + "." + d.className()
                    : modulePath;
            result.add(new DiscoveredMethod(
                    fqcn,
                    d.name(),
                    d.beginLine(),
                    d.endLine(),
                    d.loc(),
                    d.tags(),
                    null,
                    file,
                    modulePath,
                    sourceContent));
        }
    }

    // ── Package-private static helpers (accessible from tests) ────────────

    /**
     * Returns {@code true} when the given file name should be scanned for
     * Python test functions.
     *
     * <p>
     * Selection rules (applied in order):
     * </p>
     * <ol>
     *   <li>If the name starts with {@code "test_"} and ends with
     *       {@code ".py"} → accept (always active).</li>
     *   <li>If {@code configuredSuffixes} is non-empty: accept if the name
     *       ends with any of those suffixes.</li>
     *   <li>Otherwise (empty configured suffixes): accept if the name ends
     *       with the default suffix {@code "_test.py"}.</li>
     * </ol>
     *
     * @param fileName           the simple file name (no directory component)
     * @param configuredSuffixes suffixes from {@link TestDiscoveryConfig#fileSuffixesFor};
     *                           an empty list means "use defaults"
     * @return {@code true} if the file should be scanned
     */
    /* default */ static boolean isPythonTestFile(
            String fileName, List<String> configuredSuffixes) {
        if (fileName.startsWith("test_") && fileName.endsWith(".py")) {
            return true;
        }
        if (!configuredSuffixes.isEmpty()) {
            return configuredSuffixes.stream().anyMatch(fileName::endsWith);
        }
        return fileName.endsWith("_test.py");
    }

    /**
     * Computes the dot-separated module path for {@code file} relative to
     * {@code root}.
     *
     * <p>
     * Both paths are normalised before relativising.  Path segments are joined
     * with {@code "."} and the {@code ".py"} extension is stripped from the
     * last segment.
     * </p>
     *
     * <p>
     * Example: if {@code root} is {@code /project/tests} and {@code file} is
     * {@code /project/tests/auth/test_auth.py}, the result is
     * {@code "auth.test_auth"}.
     * </p>
     *
     * @param file the source file; must be inside {@code root}
     * @param root the scan root
     * @return dot-separated module path; never {@code null} or empty
     */
    /* default */ static String buildModulePath(Path file, Path root) {
        Path relative = root.normalize().relativize(file.normalize());
        int count = relative.getNameCount();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            String segment = relative.getName(i).toString();
            if (i == count - 1) {
                int dot = segment.lastIndexOf('.');
                if (dot > 0) {
                    segment = segment.substring(0, dot);
                }
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(segment);
        }
        return sb.toString();
    }
}
