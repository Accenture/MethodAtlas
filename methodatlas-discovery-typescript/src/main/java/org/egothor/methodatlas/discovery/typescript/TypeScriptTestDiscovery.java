package org.egothor.methodatlas.discovery.typescript;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.SourceContent;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;

/**
 * {@link TestDiscovery} implementation for TypeScript and JavaScript source trees.
 *
 * <p>
 * Traverses a directory root, selects files whose names end with any of the
 * configured suffixes (defaults: {@code .test.ts}, {@code .spec.ts},
 * {@code .test.tsx}, {@code .spec.tsx}, {@code .test.js}, {@code .spec.js}),
 * and delegates parsing to a pool of Node.js worker processes running
 * {@code ts-scanner.bundle.js}.
 * </p>
 *
 * <h2>Test-method detection</h2>
 *
 * <p>
 * Tests are identified by function-call names rather than annotations.
 * The configurable {@code functionNames} property (via
 * {@link TestDiscoveryConfig#properties()} key {@code "functionNames"}) lists
 * the names to recognise — default: {@code test}, {@code it}.  Calls to
 * {@code describe} / {@code context} / {@code suite} are always recognised as
 * scope wrappers; their names are prepended to the method name in the output
 * (e.g. {@code "AuthService > should authenticate users"}).
 * </p>
 *
 * <h2>Node.js availability</h2>
 *
 * <p>
 * If Node.js is not found on the {@code PATH} (or is below version 18), the
 * plugin logs a {@code WARNING} at configuration time and disables itself:
 * {@link #discover(Path)} returns an empty stream and {@link #hadErrors()}
 * returns {@code false}.  This allows MethodAtlas to be run in Java-only
 * environments without failing the scan.
 * </p>
 *
 * <h2>Resource management</h2>
 *
 * <p>
 * The worker pool holds live Node.js sub-processes.  The orchestration layer
 * calls {@link #close()} when the scan run finishes, which shuts down all
 * workers.  A JVM shutdown hook registered by the pool acts as a backstop.
 * </p>
 *
 * <h2>ServiceLoader usage</h2>
 *
 * <p>
 * This class is registered as a {@link TestDiscovery} provider via
 * {@code META-INF/services/org.egothor.methodatlas.api.TestDiscovery}.
 * When loaded that way the no-arg constructor is used and
 * {@link #configure(TestDiscoveryConfig)} must be called before
 * {@link #discover(Path)}.
 * </p>
 *
 * @see TypeScriptWorkerPool
 * @see BundleIntegrity
 * @see NodeEnvironment
 * @see TestDiscovery
 * @see TestDiscoveryConfig
 */
public final class TypeScriptTestDiscovery implements TestDiscovery {

    private static final Logger LOG = Logger.getLogger(TypeScriptTestDiscovery.class.getName());

    /**
     * Default file suffixes matched when the caller supplies none.
     *
     * <p>
     * The {@code typescript:} prefix routes these suffixes exclusively to this
     * plugin, preventing the JVM and .NET plugins from attempting to parse
     * TypeScript files.
     * </p>
     */
    /* default */ static final List<String> DEFAULT_SUFFIXES = List.of(
            ".test.ts", ".spec.ts", ".test.tsx", ".spec.tsx",
            ".test.js", ".spec.js");

    /** Default test-function call names. */
    /* default */ static final List<String> DEFAULT_FUNCTION_NAMES = List.of("test", "it");

    /**
     * Default pool size: the lesser of 4 and the number of available
     * processors, which empirically saturates a 4-core machine without
     * creating excessive Node.js processes on larger machines.
     */
    private static final int DEFAULT_POOL_SIZE =
            Math.min(4, Runtime.getRuntime().availableProcessors());

    /** Default per-file worker timeout in seconds. */
    private static final int DEFAULT_TIMEOUT_SEC = 30;

    /** Default circuit-breaker: 5 restarts within 60 seconds trips the circuit. */
    private static final int DEFAULT_MAX_RESTARTS = 5;
    private static final int DEFAULT_RESTART_WINDOW_SEC = 60;

    // -------------------------------------------------------------------------
    // Configured state (set by configure())
    // -------------------------------------------------------------------------
    private List<String> fileSuffixes;
    private List<String> functionNames;
    private int poolSize;
    private long workerTimeoutMillis;
    private int maxRestarts;
    private int restartWindowSec;

    // -------------------------------------------------------------------------
    // Runtime state (set lazily on first discover())
    // -------------------------------------------------------------------------
    /** Guards lazy initialisation of {@link #nodeEnv} and {@link #workerPool}. */
    private final ReentrantLock poolInitLock = new ReentrantLock();
    private NodeEnvironment nodeEnv;
    private TypeScriptWorkerPool workerPool;
    private boolean configured;
    private boolean errors;

    /**
     * No-arg constructor for use by {@link java.util.ServiceLoader}.
     *
     * <p>
     * {@link #configure(TestDiscoveryConfig)} must be called before the first
     * call to {@link #discover(Path)}.
     * </p>
     */
    public TypeScriptTestDiscovery() {
        // Required by ServiceLoader; call configure(TestDiscoveryConfig) before first use.
    }

    /**
     * Returns the unique identifier of this discovery provider: {@code "typescript"}.
     *
     * @return {@code "typescript"}
     */
    @Override
    public String pluginId() {
        return "typescript";
    }

    /**
     * Configures this provider from a {@link TestDiscoveryConfig}.
     *
     * <p>
     * Reads the following configuration knobs:
     * </p>
     * <ul>
     * <li><b>File suffixes</b> — via {@link TestDiscoveryConfig#fileSuffixesFor(String)}
     *     with ID {@code "typescript"}.  Falls back to {@link #DEFAULT_SUFFIXES}.</li>
     * <li><b>{@code functionNames}</b> — property key in
     *     {@link TestDiscoveryConfig#properties()}; default:
     *     {@link #DEFAULT_FUNCTION_NAMES}.</li>
     * <li><b>{@code typescript.poolSize}</b> — number of worker processes;
     *     default: {@link #DEFAULT_POOL_SIZE}.</li>
     * <li><b>{@code typescript.workerTimeoutSec}</b> — per-file worker timeout;
     *     default: {@value #DEFAULT_TIMEOUT_SEC} s.</li>
     * <li><b>{@code typescript.maxConsecutiveRestarts}</b> — circuit-breaker
     *     restart limit; default: {@value #DEFAULT_MAX_RESTARTS}.</li>
     * <li><b>{@code typescript.restartWindowSec}</b> — circuit-breaker sliding
     *     window; default: {@value #DEFAULT_RESTART_WINDOW_SEC} s.</li>
     * </ul>
     *
     * <p>
     * This method does <em>not</em> start the worker pool.  The pool is started
     * lazily on the first call to {@link #discover(Path)} so that Node.js
     * detection and bundle extraction only happen when TypeScript scanning is
     * actually needed.
     * </p>
     *
     * @param config runtime configuration; never {@code null}
     */
    @Override
    public void configure(TestDiscoveryConfig config) {
        List<String> suffixes = config.fileSuffixesFor(pluginId());
        this.fileSuffixes = suffixes.isEmpty() ? DEFAULT_SUFFIXES : suffixes;

        List<String> fns = config.properties().getOrDefault("functionNames", List.of());
        this.functionNames = fns.isEmpty() ? DEFAULT_FUNCTION_NAMES : List.copyOf(fns);

        this.poolSize = parseIntProperty(config, "typescript.poolSize", DEFAULT_POOL_SIZE);
        int timeoutSec = parseIntProperty(config, "typescript.workerTimeoutSec", DEFAULT_TIMEOUT_SEC);
        this.workerTimeoutMillis = timeoutSec * 1_000L;
        this.maxRestarts = parseIntProperty(config, "typescript.maxConsecutiveRestarts",
                DEFAULT_MAX_RESTARTS);
        this.restartWindowSec = parseIntProperty(config, "typescript.restartWindowSec",
                DEFAULT_RESTART_WINDOW_SEC);

        this.configured = true;
    }

    /**
     * Scans {@code root} and returns a stream of all discovered TypeScript test
     * methods.
     *
     * <p>
     * On the first call, initialises Node.js detection, bundle extraction, and
     * the worker pool.  If Node.js is not available the method returns an empty
     * stream immediately.  Files that cannot be parsed are logged as warnings
     * and skipped; {@link #hadErrors()} will return {@code true} after such a
     * run.
     * </p>
     *
     * @param root directory to scan
     * @return stream of discovered test methods; never {@code null}
     * @throws IllegalStateException if {@link #configure} has not been called
     *                               on an instance created with the no-arg
     *                               constructor
     * @throws IOException           if traversing the file tree fails
     */
    @Override
    public Stream<DiscoveredMethod> discover(Path root) throws IOException {
        if (!configured) {
            throw new IllegalStateException(
                    "TypeScriptTestDiscovery is not configured. "
                    + "Call configure(TestDiscoveryConfig) before discover(Path).");
        }

        ensurePoolReady();

        if (nodeEnv == null || !nodeEnv.isAvailable() || workerPool == null) {
            return Stream.empty(); // Node.js unavailable — plugin is disabled
        }

        if (LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO, "Scanning {0} for files matching {1}",
                    new Object[] { root, fileSuffixes });
        }

        List<DiscoveredMethod> result = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk
                    .filter(p -> fileSuffixes.stream()
                            .anyMatch(s -> p.getFileName() != null
                                    && p.getFileName().toString().endsWith(s)))
                    .toList();

            for (Path file : files) {
                processFile(root, file, result);
            }
        }

        return result.stream();
    }

    /** {@inheritDoc} */
    @Override
    public boolean hadErrors() {
        return errors;
    }

    /**
     * Shuts down the worker pool and removes the JVM shutdown hook registered
     * by the pool.
     *
     * <p>
     * This method is idempotent: calling it on a provider that was never
     * started (e.g. Node.js was not available) is safe.
     * </p>
     *
     * @throws IOException never thrown by this implementation; declared to
     *                     satisfy the {@link java.io.Closeable} contract
     */
    @Override
    public void close() throws IOException {
        if (workerPool != null) {
            workerPool.close();
            workerPool = null;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Ensures Node.js has been detected and the worker pool has been started.
     * Idempotent: subsequent calls are no-ops.
     */
    private void ensurePoolReady() {
        poolInitLock.lock();
        try {
            if (nodeEnv != null) {
                return; // already initialised
            }
            nodeEnv = new NodeEnvironment();
            if (!nodeEnv.isAvailable()) {
                return; // worker pool will not be created
            }
            try {
                Path bundlePath = BundleIntegrity.extractAndVerify();
                WorkerCircuitBreaker cb = new WorkerCircuitBreaker(maxRestarts, restartWindowSec);
                workerPool = new TypeScriptWorkerPool(
                        bundlePath, nodeEnv, poolSize, workerTimeoutMillis, cb);
            } catch (IOException | IllegalStateException e) {
                LOG.log(Level.SEVERE,
                        "Failed to initialise TypeScript worker pool — TypeScript scanning disabled", e);
                nodeEnv = null; // treat as unavailable
            }
        } finally {
            poolInitLock.unlock();
        }
    }

    /**
     * Processes a single TypeScript file: sends it to a worker, converts the
     * response into {@link DiscoveredMethod} records, and appends them to
     * {@code result}.
     *
     * @param root    scan root (for path-stem computation)
     * @param file    absolute path to the source file
     * @param result  accumulator for discovered methods
     * @throws IOException if reading the source file fails
     */
    private void processFile(Path root, Path file, List<DiscoveredMethod> result)
            throws IOException {

        List<TypeScriptWorker.MethodDescriptor> descriptors;
        try {
            descriptors = workerPool.scan(file, functionNames, root);
        } catch (IOException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Cannot scan TypeScript file: " + file, e);
            }
            errors = true;
            return;
        }

        if (descriptors.isEmpty()) {
            return;
        }

        // Build the FQCN from the file path (relative to root, dot-separated, no extension).
        String fqcn = buildFqcn(root, file);
        String fileStem = buildFileStem(root, file);

        // Lazy-load the source content once per file, shared across all methods.
        SourceContent sourceContent = buildSourceContent(file);

        for (TypeScriptWorker.MethodDescriptor d : descriptors) {
            String methodName = buildMethodName(d);
            result.add(new DiscoveredMethod(
                    fqcn,
                    methodName,
                    d.beginLine(),
                    d.endLine(),
                    d.loc(),
                    List.of(),    // TypeScript tests have no annotation-based tags
                    null,         // displayName: the test name already serves as the label
                    file,
                    fileStem,
                    sourceContent));
        }
    }

    /**
     * Computes the fully qualified "class name" for TypeScript test methods.
     *
     * <p>
     * In TypeScript there are no classes; the file is the natural grouping
     * unit.  The FQCN is the relative path from the scan root to the source
     * file, with path separators replaced by {@code .} and the file extension
     * stripped.  This means all tests in the same file share the same FQCN.
     * </p>
     *
     * <p>Example: {@code auth/__tests__/authService.test.ts} scanned from
     * root → {@code auth.__tests__.authService.test}</p>
     *
     * @param root scan root directory
     * @param file source file
     * @return dot-separated FQCN; never {@code null}
     */
    /* default */ static String buildFqcn(Path root, Path file) {
        Path rel = root.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize());
        String pathStr = rel.toString().replace('\\', '/').replace('/', '.');
        // Strip the file extension (last component only).
        int lastDot = pathStr.lastIndexOf('.');
        if (lastDot > 0) {
            pathStr = pathStr.substring(0, lastDot);
        }
        return pathStr;
    }

    /**
     * Computes the dot-separated file stem used to name work and response files
     * in the manual AI workflow.  Identical to {@link #buildFqcn(Path, Path)}
     * for TypeScript because there is no class hierarchy below the file level.
     *
     * @param root scan root directory
     * @param file source file
     * @return dot-separated file stem; never {@code null}
     */
    /* default */ static String buildFileStem(Path root, Path file) {
        return buildFqcn(root, file);
    }

    /**
     * Constructs the method name from a worker method descriptor.
     *
     * <p>
     * When the test is nested inside one or more {@code describe} blocks, the
     * block names are prepended with {@code " > "} separators to form a
     * readable path (e.g. {@code "AuthService > login > should accept valid credentials"}).
     * This mirrors the format that Jest uses in its own test-result output.
     * </p>
     *
     * @param d method descriptor from the worker
     * @return human-readable method name; never {@code null}
     */
    private static String buildMethodName(TypeScriptWorker.MethodDescriptor d) {
        if (d.describe() == null || d.describe().isEmpty()) {
            return d.name();
        }
        return String.join(" > ", d.describe()) + " > " + d.name();
    }

    /**
     * Creates a lazy {@link SourceContent} provider that reads and caches the
     * full content of {@code file} on the first call to
     * {@link SourceContent#get()}.
     *
     * <p>
     * The lazy approach avoids reading file content when AI analysis is
     * disabled, matching the behaviour of the JVM and .NET plugins.
     * All methods discovered in the same file share a single
     * {@link SourceContent} instance to avoid redundant reads.
     * </p>
     *
     * @param file source file
     * @return lazy source-content provider
     */
    private static SourceContent buildSourceContent(Path file) {
        // Single cached read; AtomicBoolean/AtomicReference allow safe capture in lambda.
        AtomicBoolean read = new AtomicBoolean(false);
        AtomicReference<String> cache = new AtomicReference<>(null);
        return () -> {
            if (read.compareAndSet(false, true)) {
                try {
                    cache.set(Files.readString(file));
                } catch (IOException e) {
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.log(Level.FINE, "Cannot read source for AI analysis: " + file, e);
                    }
                }
            }
            return Optional.ofNullable(cache.get());
        };
    }

    /**
     * Reads a single integer property from the configuration.
     *
     * @param config       discovery configuration
     * @param key          property key
     * @param defaultValue value to use when the key is absent or unparseable
     * @return parsed integer value
     */
    private static int parseIntProperty(TestDiscoveryConfig config, String key, int defaultValue) {
        List<String> values = config.properties().get(key);
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(values.get(0));
        } catch (NumberFormatException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.warning("Invalid value for property '" + key + "': " + values.get(0)
                        + " — using default " + defaultValue);
            }
            return defaultValue;
        }
    }
}
