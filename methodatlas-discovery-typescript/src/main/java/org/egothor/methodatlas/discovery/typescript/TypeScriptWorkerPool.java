package org.egothor.methodatlas.discovery.typescript;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.egothor.methodatlas.util.WorkerCircuitBreaker;
import org.egothor.methodatlas.util.WorkerPool;

/**
 * Manages a pool of long-lived Node.js worker processes for TypeScript file
 * scanning.
 *
 * <p>
 * The generic lifecycle (lazy on-demand creation, the idle-worker queue,
 * circuit-breaker-tracked restarts, and shutdown-hook teardown) is provided by
 * {@link WorkerPool}.  This subclass supplies only the Node.js-specific worker
 * creation and termination and a strongly-typed {@link #scan} entry point.
 * The per-request creation context is the scan root {@link Path}, which is
 * forwarded to {@link TypeScriptWorker#start(Path)} for filesystem sandboxing
 * when a fresh worker is started.
 * </p>
 *
 * @see WorkerPool
 * @see TypeScriptWorker
 */
final class TypeScriptWorkerPool extends WorkerPool<TypeScriptWorker, Path> {

    private static final Logger LOG = Logger.getLogger(TypeScriptWorkerPool.class.getName());

    private final Path bundlePath;
    private final NodeEnvironment nodeEnv;
    private final long workerTimeoutMillis;

    /**
     * Creates a worker pool.  No workers are started at construction time.
     *
     * @param bundlePath          path to the verified bundle JS file
     * @param nodeEnv             Node.js environment information
     * @param poolSize            maximum number of concurrent workers; must be positive
     * @param workerTimeoutMillis per-request timeout in milliseconds
     * @param circuitBreaker      restart-limit tracker shared with this pool
     */
    /* default */ TypeScriptWorkerPool(Path bundlePath, NodeEnvironment nodeEnv,
            int poolSize, long workerTimeoutMillis, WorkerCircuitBreaker circuitBreaker) {
        super("TypeScript", "ts-worker-pool-shutdown", poolSize, circuitBreaker);
        this.bundlePath = bundlePath;
        this.nodeEnv = nodeEnv;
        this.workerTimeoutMillis = workerTimeoutMillis;
    }

    @Override
    protected TypeScriptWorker createWorker(int index, Path allowedRoot) throws IOException {
        TypeScriptWorker worker = new TypeScriptWorker(bundlePath, nodeEnv, workerTimeoutMillis, index);
        worker.start(allowedRoot);
        return worker;
    }

    @Override
    protected void kill(TypeScriptWorker worker, String reason) {
        worker.kill(reason);
    }

    /**
     * Scans one TypeScript file by delegating to a pooled worker.
     *
     * @param filePath      absolute path of the file to scan
     * @param functionNames test-function call names
     * @param allowedRoot   scan root for permission sandboxing; forwarded to a
     *                      newly started worker; may be {@code null}
     * @return list of discovered method descriptors; empty when the circuit is
     *         open or a non-recoverable error occurs
     * @throws IOException if borrowing a worker fails with a hard I/O error
     */
    /* default */ List<TypeScriptWorker.MethodDescriptor> scan(
            Path filePath, List<String> functionNames, Path allowedRoot) throws IOException {

        if (isCircuitOpen()) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.warning("TypeScript worker pool circuit breaker is open — skipping " + filePath);
            }
            return List.of();
        }

        TypeScriptWorker worker = borrow(allowedRoot);
        if (worker == null) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.warning("No TypeScript worker available within " + borrowTimeoutMillis()
                        + " ms — skipping " + filePath);
            }
            return List.of();
        }

        try {
            List<TypeScriptWorker.MethodDescriptor> result =
                    worker.scan(filePath, functionNames);
            returnWorker(worker);
            return result;
        } catch (TypeScriptWorker.WorkerException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING,
                        "TypeScript worker error scanning " + filePath + " — "
                        + "killing and replacing worker: " + e.getMessage(), e);
            }
            worker.kill("scan error: " + e.getMessage());
            replaceWorker(allowedRoot);
            return List.of();
        }
    }
}
