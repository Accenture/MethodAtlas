package org.egothor.methodatlas.discovery.typescript;

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
 * Manages a pool of long-lived Node.js worker processes for TypeScript
 * file scanning.
 *
 * <p>
 * Workers are started on demand when the first scan request arrives and
 * returned to a shared idle queue after each request completes.  Using a pool
 * avoids the Node.js startup overhead (typically 100–300 ms) on every file,
 * which would otherwise dominate scan time for large TypeScript projects.
 * Because workers are started lazily, projects that contain no TypeScript or
 * JavaScript test files never spawn a Node.js process at all.
 * </p>
 *
 * <h2>Worker lifecycle</h2>
 *
 * <ol>
 * <li>Workers are started one by one on the first {@link #scan} calls, up to
 *     {@code poolSize} total.</li>
 * <li>On each scan request, a worker is borrowed from the idle queue.</li>
 * <li>After a successful response the worker is returned to the queue.</li>
 * <li>On failure ({@link TypeScriptWorker.WorkerException} or I/O error),
 *     the worker is killed, a restart event is recorded in the
 *     {@link WorkerCircuitBreaker}, and a fresh worker is created to replace
 *     it — unless the circuit has already tripped.</li>
 * </ol>
 *
 * <h2>Circuit-breaker integration</h2>
 *
 * <p>
 * The pool delegates restart tracking to a shared {@link WorkerCircuitBreaker}.
 * When the circuit opens, no new workers are created.  Scan requests after
 * that point receive an empty result and a warning is logged once per request.
 * </p>
 *
 * <h2>Shutdown</h2>
 *
 * <p>
 * {@link #close()} drains the idle queue, kills all workers, and removes the
 * JVM shutdown hook registered at construction time.  Callers (typically the
 * {@link TypeScriptTestDiscovery}) must call {@code close()} at the end of the
 * scan run.  A JVM shutdown hook serves as a backstop in case {@code close()}
 * is not reached (e.g. an abrupt exit from a test framework).
 * </p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * The pool itself is thread-safe: the {@link BlockingQueue} provides the
 * necessary synchronisation for worker borrowing and returning.  The
 * {@link WorkerCircuitBreaker} is also thread-safe.  Worker instances
 * returned by {@link #borrow(Path)} must be used by at most one thread at a time.
 * </p>
 */
final class TypeScriptWorkerPool implements Closeable {

    private static final Logger LOG = Logger.getLogger(TypeScriptWorkerPool.class.getName());

    /** Milliseconds to wait for a worker to become available in the idle queue. */
    private static final long BORROW_TIMEOUT_MILLIS = 10_000L;

    private final Path bundlePath;
    private final NodeEnvironment nodeEnv;
    private final long workerTimeoutMillis;
    private final WorkerCircuitBreaker circuitBreaker;
    private final BlockingQueue<TypeScriptWorker> idleWorkers;
    private final int poolSize;
    @SuppressWarnings("PMD.DoNotUseThreads")
    private final Thread shutdownHook;

    /** Guards {@link #nextWorkerIndex} during worker creation. */
    private final ReentrantLock workerCreationLock = new ReentrantLock();

    /** Guarded by {@link #workerCreationLock}: number of workers ever created (for index assignment). */
    private int nextWorkerIndex;

    /**
     * Creates a worker pool.
     *
     * <p>
     * No workers are started at construction time.  The first worker is
     * started on demand when {@link #scan} is called for the first time.
     * This means that projects containing no TypeScript or JavaScript test
     * files never spawn a Node.js process.
     * </p>
     *
     * @param bundlePath           path to the verified bundle JS file
     * @param nodeEnv              Node.js environment information
     * @param poolSize             maximum number of concurrent workers; must be positive
     * @param workerTimeoutMillis  per-request timeout in milliseconds
     * @param circuitBreaker       restart-limit tracker shared with this pool
     */
    /* default */
    @SuppressWarnings("PMD.DoNotUseThreads")
    TypeScriptWorkerPool(Path bundlePath, NodeEnvironment nodeEnv,
            int poolSize, long workerTimeoutMillis, WorkerCircuitBreaker circuitBreaker) {
        this.bundlePath = bundlePath;
        this.nodeEnv = nodeEnv;
        this.poolSize = poolSize;
        this.workerTimeoutMillis = workerTimeoutMillis;
        this.circuitBreaker = circuitBreaker;
        this.idleWorkers = new ArrayBlockingQueue<>(poolSize);

        // Register a JVM shutdown hook as a safety net in case close() is never called.
        this.shutdownHook = new Thread(this::shutdownAllWorkers, "ts-worker-pool-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * Scans one TypeScript file by delegating to a pooled worker.
     *
     * <p>
     * The worker is borrowed from the idle queue, the request is sent, and
     * the worker is returned after a successful response.  On failure the
     * worker is killed, replaced if the circuit is still closed, and the error
     * is handled as a scan-file error (the caller logs and skips the file
     * rather than aborting the run).
     * </p>
     *
     * @param filePath      absolute path of the file to scan
     * @param functionNames test-function call names
     * @param allowedRoot   scan root for permission sandboxing; passed to
     *                      the worker on restart; may be {@code null}
     * @return list of discovered method descriptors; empty when the circuit is
     *         open or a non-recoverable error occurs
     * @throws IOException if borrowing a worker fails with a hard I/O error
     */
    /* default */ List<TypeScriptWorker.MethodDescriptor> scan(
            Path filePath, List<String> functionNames, Path allowedRoot) throws IOException {

        if (circuitBreaker.isOpen()) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.warning("TypeScript worker pool circuit breaker is open — "
                        + "skipping " + filePath);
            }
            return List.of();
        }

        TypeScriptWorker worker = borrow(allowedRoot);
        if (worker == null) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.warning("No TypeScript worker available within " + BORROW_TIMEOUT_MILLIS
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

    /**
     * Shuts down all workers and removes the JVM shutdown hook.
     *
     * <p>
     * This method is idempotent and safe to call from any thread.
     * </p>
     */
    @Override
    public void close() {
        shutdownAllWorkers();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // JVM is already shutting down; hook removal is not possible.
            if (LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "Could not remove shutdown hook (JVM shutting down)", e);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Creates and starts a worker.
     * <p><b>Caller must hold {@link #workerCreationLock}.</b></p>
     */
    private TypeScriptWorker createWorkerUnderLock(Path allowedRoot) throws IOException {
        int index = nextWorkerIndex++;
        TypeScriptWorker worker = new TypeScriptWorker(bundlePath, nodeEnv,
                workerTimeoutMillis, index);
        worker.start(allowedRoot);
        return worker;
    }

    /**
     * Borrows an idle worker, starting one on demand if the pool has not yet
     * reached {@link #poolSize}, then waiting up to {@link #BORROW_TIMEOUT_MILLIS} ms.
     *
     * @param allowedRoot scan root forwarded to a newly started worker's permission flag
     * @return an idle worker, or {@code null} on timeout
     */
    @SuppressWarnings("PMD.DoNotUseThreads")
    private TypeScriptWorker borrow(Path allowedRoot) {
        startWorkerOnDemand(allowedRoot);
        try {
            return idleWorkers.poll(BORROW_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Starts one new worker and places it in the idle queue if the queue is
     * currently empty and the pool has not yet reached its maximum size.
     * Concurrent callers are serialised by {@link #workerCreationLock}; the
     * condition is rechecked inside the lock to avoid starting duplicate workers.
     */
    private void startWorkerOnDemand(Path allowedRoot) {
        if (!idleWorkers.isEmpty() || nextWorkerIndex >= poolSize) {
            return; // fast path: worker already available or pool is full
        }
        workerCreationLock.lock();
        try {
            if (!idleWorkers.isEmpty() || nextWorkerIndex >= poolSize) {
                return; // another thread already started one
            }
            TypeScriptWorker worker = createWorkerUnderLock(allowedRoot);
            idleWorkers.offer(worker);
        } catch (IOException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING,
                        "Failed to start TypeScript worker on demand (index " + nextWorkerIndex + ")", e);
            }
        } finally {
            workerCreationLock.unlock();
        }
    }

    /** Returns a worker to the idle queue. */
    private void returnWorker(TypeScriptWorker worker) {
        if (!idleWorkers.offer(worker)) {
            // Queue full (should not happen in a properly sized pool), kill the surplus.
            worker.kill("pool queue full on return");
        }
    }

    /**
     * Records a restart event in the circuit breaker and, if the circuit is
     * still closed, starts a replacement worker and puts it in the idle queue.
     *
     * @param allowedRoot scan root for the replacement worker's permission flag
     */
    private void replaceWorker(Path allowedRoot) {
        circuitBreaker.recordRestart();
        if (circuitBreaker.isOpen()) {
            return; // circuit just tripped; don't create more workers
        }
        workerCreationLock.lock();
        try {
            TypeScriptWorker replacement = createWorkerUnderLock(allowedRoot);
            if (!idleWorkers.offer(replacement)) {
                replacement.kill("pool queue full after replacement");
            }
        } catch (IOException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Failed to start replacement TypeScript worker", e);
            }
        } finally {
            workerCreationLock.unlock();
        }
    }

    /** Drains the idle queue and kills all workers. */
    private void shutdownAllWorkers() {
        List<TypeScriptWorker> workers = new ArrayList<>(poolSize);
        idleWorkers.drainTo(workers);
        for (TypeScriptWorker w : workers) {
            w.kill("pool shutdown");
        }
        if (LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO, "TypeScript worker pool shut down ({0} worker(s) stopped)",
                    workers.size());
        }
    }
}
