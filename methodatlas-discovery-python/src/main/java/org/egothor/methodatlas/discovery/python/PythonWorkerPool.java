package org.egothor.methodatlas.discovery.python;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages a pool of long-lived Python worker processes for Python test file
 * scanning.
 *
 * <p>
 * Workers are started on demand when the first scan request arrives and
 * returned to a shared idle queue after each request completes.  Using a pool
 * avoids the Python startup overhead on every file, which would otherwise
 * dominate scan time for large Python projects.  Because workers are started
 * lazily, projects that contain no Python test files never spawn a Python
 * process at all.
 * </p>
 *
 * <h2>Worker lifecycle</h2>
 *
 * <ol>
 * <li>Workers are started one by one on the first {@link #scan} calls, up to
 *     {@code poolSize} total.</li>
 * <li>On each scan request, a worker is borrowed from the idle queue.</li>
 * <li>After a successful response the worker is returned to the queue.</li>
 * <li>On failure, the worker is killed and a replacement is created unless
 *     the circuit breaker has tripped.</li>
 * </ol>
 *
 * <h2>Shutdown</h2>
 *
 * <p>
 * {@link #close()} drains the idle queue, kills all workers, and removes the
 * JVM shutdown hook.  A JVM shutdown hook serves as a backstop.
 * </p>
 */
final class PythonWorkerPool implements Closeable {

    private static final Logger LOG = Logger.getLogger(PythonWorkerPool.class.getName());

    private static final long BORROW_TIMEOUT_MILLIS = 10_000L;

    private final Path scriptPath;
    private final PythonEnvironment pythonEnv;
    private final long workerTimeoutMillis;
    private final PythonWorkerCircuitBreaker circuitBreaker;
    private final BlockingQueue<PythonWorker> idleWorkers;
    private final int poolSize;
    @SuppressWarnings("PMD.DoNotUseThreads")
    private final Thread shutdownHook;

    private final ReentrantLock workerCreationLock = new ReentrantLock();
    private int nextWorkerIndex;

    /**
     * Creates a worker pool.  No workers are started at construction time.
     *
     * @param scriptPath          path to the extracted {@code py-scanner.py} script
     * @param pythonEnv           Python environment information
     * @param poolSize            maximum number of concurrent workers; must be positive
     * @param workerTimeoutMillis per-request timeout in milliseconds
     * @param circuitBreaker      restart-limit tracker shared with this pool
     */
    @SuppressWarnings("PMD.DoNotUseThreads")
    /* default */ PythonWorkerPool(Path scriptPath, PythonEnvironment pythonEnv,
            int poolSize, long workerTimeoutMillis, PythonWorkerCircuitBreaker circuitBreaker) {
        this.scriptPath = scriptPath;
        this.pythonEnv = pythonEnv;
        this.poolSize = poolSize;
        this.workerTimeoutMillis = workerTimeoutMillis;
        this.circuitBreaker = circuitBreaker;
        this.idleWorkers = new ArrayBlockingQueue<>(poolSize);

        this.shutdownHook = new Thread(this::shutdownAllWorkers, "py-worker-pool-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
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
        if (circuitBreaker.isOpen()) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.warning("Python worker pool circuit breaker is open — skipping " + filePath);
            }
            return List.of();
        }

        PythonWorker worker = borrow();
        if (worker == null) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.warning("No Python worker available within " + BORROW_TIMEOUT_MILLIS
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
            replaceWorker();
            return List.of();
        }
    }

    /**
     * Shuts down all workers and removes the JVM shutdown hook.  Idempotent.
     */
    @Override
    public void close() {
        shutdownAllWorkers();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "Could not remove shutdown hook (JVM shutting down)", e);
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────

    private PythonWorker createWorkerUnderLock() throws IOException {
        int index = nextWorkerIndex++;
        PythonWorker worker = new PythonWorker(scriptPath, pythonEnv, workerTimeoutMillis, index);
        worker.start();
        return worker;
    }

    @SuppressWarnings("PMD.DoNotUseThreads")
    private PythonWorker borrow() {
        startWorkerOnDemand();
        try {
            return idleWorkers.poll(BORROW_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void startWorkerOnDemand() {
        if (!idleWorkers.isEmpty() || nextWorkerIndex >= poolSize) {
            return;
        }
        workerCreationLock.lock();
        try {
            if (!idleWorkers.isEmpty() || nextWorkerIndex >= poolSize) {
                return;
            }
            PythonWorker worker = createWorkerUnderLock();
            idleWorkers.offer(worker);
        } catch (IOException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING,
                        "Failed to start Python worker on demand (index " + nextWorkerIndex + ")", e);
            }
        } finally {
            workerCreationLock.unlock();
        }
    }

    private void returnWorker(PythonWorker worker) {
        if (!idleWorkers.offer(worker)) {
            worker.kill("pool queue full on return");
        }
    }

    private void replaceWorker() {
        circuitBreaker.recordRestart();
        if (circuitBreaker.isOpen()) {
            return;
        }
        workerCreationLock.lock();
        try {
            PythonWorker replacement = createWorkerUnderLock();
            if (!idleWorkers.offer(replacement)) {
                replacement.kill("pool queue full after replacement");
            }
        } catch (IOException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Failed to start replacement Python worker", e);
            }
        } finally {
            workerCreationLock.unlock();
        }
    }

    private void shutdownAllWorkers() {
        List<PythonWorker> workers = new ArrayList<>(poolSize);
        idleWorkers.drainTo(workers);
        for (PythonWorker w : workers) {
            w.kill("pool shutdown");
        }
        if (LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO, "Python worker pool shut down ({0} worker(s) stopped)",
                    workers.size());
        }
    }
}
