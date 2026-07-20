package org.egothor.methodatlas.discovery.python;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.egothor.methodatlas.util.WorkerCircuitBreaker;
import org.egothor.methodatlas.util.WorkerPool;

/**
 * Manages a pool of long-lived Python worker processes for Python test file
 * scanning.
 *
 * <p>
 * The generic lifecycle (lazy on-demand creation, the idle-worker queue,
 * circuit-breaker-tracked restarts, and shutdown-hook teardown) is provided by
 * {@link WorkerPool}.  This subclass supplies only the Python-specific worker
 * creation and termination and a strongly-typed {@link #scan(Path)} entry point.
 * Because Python workers need no per-request creation context, the context type
 * is {@link Void}.
 * </p>
 *
 * @see WorkerPool
 * @see PythonWorker
 */
final class PythonWorkerPool extends WorkerPool<PythonWorker, Void> {

    private static final Logger LOG = Logger.getLogger(PythonWorkerPool.class.getName());

    private final Path scriptPath;
    private final PythonEnvironment pythonEnv;
    private final long workerTimeoutMillis;

    /**
     * Creates a worker pool.  No workers are started at construction time.
     *
     * @param scriptPath          path to the extracted {@code py-scanner.py} script
     * @param pythonEnv           Python environment information
     * @param poolSize            maximum number of concurrent workers; must be positive
     * @param workerTimeoutMillis per-request timeout in milliseconds
     * @param circuitBreaker      restart-limit tracker shared with this pool
     */
    /* default */ PythonWorkerPool(Path scriptPath, PythonEnvironment pythonEnv,
            int poolSize, long workerTimeoutMillis, WorkerCircuitBreaker circuitBreaker) {
        super("Python", "py-worker-pool-shutdown", poolSize, circuitBreaker);
        this.scriptPath = scriptPath;
        this.pythonEnv = pythonEnv;
        this.workerTimeoutMillis = workerTimeoutMillis;
    }

    @Override
    protected PythonWorker createWorker(int index, Void context) throws IOException {
        PythonWorker worker = new PythonWorker(scriptPath, pythonEnv, workerTimeoutMillis, index);
        worker.start();
        return worker;
    }

    @Override
    protected void kill(PythonWorker worker, String reason) {
        worker.kill(reason);
    }

    /**
     * Scans one Python file by delegating to a pooled worker.
     *
     * @param filePath absolute path of the file to scan
     * @return list of discovered method descriptors; empty when the circuit is
     *         open or a non-recoverable error occurs
     * @throws IOException if borrowing a worker fails with a hard I/O error
     */
    /* default */ List<PythonWorker.MethodDescriptor> scan(Path filePath) throws IOException {
        if (isCircuitOpen()) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.warning("Python worker pool circuit breaker is open — skipping " + filePath);
            }
            return List.of();
        }

        PythonWorker worker = borrow(null);
        if (worker == null) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.warning("No Python worker available within " + borrowTimeoutMillis()
                        + " ms — skipping " + filePath);
            }
            return List.of();
        }

        try {
            List<PythonWorker.MethodDescriptor> result = worker.scan(filePath);
            returnWorker(worker);
            return result;
        } catch (PythonWorker.WorkerException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING,
                        "Python worker error scanning " + filePath + " — killing and replacing: "
                        + e.getMessage(), e);
            }
            worker.kill("scan error: " + e.getMessage());
            replaceWorker(null);
            return List.of();
        }
    }
}
