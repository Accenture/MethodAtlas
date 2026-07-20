package org.egothor.methodatlas.util;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generic pool of long-lived external worker processes.
 *
 * <p>
 * This base class captures the concurrency-sensitive lifecycle logic shared by
 * every discovery plugin that delegates parsing to an out-of-process worker
 * (currently the TypeScript/Node.js and Python plugins): lazy on-demand worker
 * creation, an idle-worker queue, restart tracking through a shared
 * {@link WorkerCircuitBreaker}, and JVM-shutdown-hook-backed teardown.
 * Subclasses supply only the two worker-specific operations — how to create and
 * start a worker, and how to kill one — plus a thin, strongly-typed
 * {@code scan(...)} method that orchestrates a single request via
 * {@link #borrow(Object)}, {@link #returnWorker(Object)}, and
 * {@link #replaceWorker(Object)}.
 * </p>
 *
 * <h2>Worker lifecycle</h2>
 *
 * <ol>
 * <li>Workers are started one by one on the first scan requests, up to
 *     {@code poolSize} total.</li>
 * <li>On each scan request, a worker is borrowed from the idle queue.</li>
 * <li>After a successful response the worker is returned to the queue.</li>
 * <li>On failure the worker is killed, a restart event is recorded in the
 *     {@link WorkerCircuitBreaker}, and a fresh worker is created to replace it
 *     — unless the circuit has already tripped.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * The pool itself is thread-safe: the {@link BlockingQueue} provides the
 * necessary synchronisation for worker borrowing and returning, and worker
 * creation is serialised by an internal {@link ReentrantLock}.  Individual
 * worker instances returned by {@link #borrow(Object)} must be used by at most
 * one thread at a time.
 * </p>
 *
 * @param <W> the worker type managed by this pool
 * @param <C> the per-request creation context threaded into
 *            {@link #createWorker(int, Object)} (e.g. the scan root for
 *            filesystem sandboxing); use {@link Void} when no context is needed
 */
public abstract class WorkerPool<W, C> implements Closeable {

    private static final Logger LOG = Logger.getLogger(WorkerPool.class.getName());

    /** Milliseconds to wait for a worker to become available in the idle queue. */
    private static final long BORROW_TIMEOUT_MILLIS = 10_000L;

    private final String subject;
    private final int poolSize;
    private final WorkerCircuitBreaker circuitBreaker;
    private final BlockingQueue<W> idleWorkers;

    /** Guards {@link #nextWorkerIndex} and worker creation. */
    private final ReentrantLock workerCreationLock = new ReentrantLock();

    /**
     * Number of workers ever created (for index assignment).  An
     * {@link AtomicInteger} so the lock-free fast-path read in
     * {@link #startWorkerOnDemand(Object)} observes a coherent value; the
     * increment runs under {@link #workerCreationLock}.
     */
    private final AtomicInteger nextWorkerIndex = new AtomicInteger();

    @SuppressWarnings("PMD.DoNotUseThreads")
    private final Thread shutdownHook;

    /**
     * Creates a worker pool.  No workers are started at construction time; the
     * first worker is started on demand on the first {@link #borrow(Object)}.
     *
     * @param subject            human-readable plugin label for log messages
     *                           (e.g. {@code "TypeScript"}); never {@code null}
     * @param shutdownThreadName name for the JVM shutdown-hook thread
     * @param poolSize           maximum number of concurrent workers; must be positive
     * @param circuitBreaker     restart-limit tracker shared with this pool
     */
    @SuppressWarnings("PMD.DoNotUseThreads")
    protected WorkerPool(String subject, String shutdownThreadName,
            int poolSize, WorkerCircuitBreaker circuitBreaker) {
        this.subject = subject;
        this.poolSize = poolSize;
        this.circuitBreaker = circuitBreaker;
        this.idleWorkers = new ArrayBlockingQueue<>(poolSize);

        // Register a JVM shutdown hook as a safety net in case close() is never called.
        this.shutdownHook = new Thread(this::shutdownAllWorkers, shutdownThreadName);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    // -------------------------------------------------------------------------
    // Worker-specific operations supplied by subclasses
    // -------------------------------------------------------------------------

    /**
     * Creates and starts a fresh worker.
     *
     * <p>
     * Called while the pool's internal creation lock is held, so
     * implementations need not add their own synchronisation.
     * </p>
     *
     * @param index   zero-based index assigned to the new worker
     * @param context per-request creation context (may be {@code null})
     * @return a started worker ready to accept scan requests
     * @throws IOException if the worker process cannot be started
     */
    protected abstract W createWorker(int index, C context) throws IOException;

    /**
     * Terminates a worker.  Implementations must be idempotent and must not
     * throw.
     *
     * @param worker the worker to kill
     * @param reason human-readable reason (for the worker's own logging)
     */
    protected abstract void kill(W worker, String reason);

    // -------------------------------------------------------------------------
    // Protected API used by subclass scan() implementations
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the shared circuit breaker has tripped and no
     * further worker activity should be attempted.
     *
     * @return {@code true} if the circuit is open
     */
    protected final boolean isCircuitOpen() {
        return circuitBreaker.isOpen();
    }

    /**
     * The human-readable plugin label supplied at construction, for use in
     * subclass log messages.
     *
     * @return the plugin label (e.g. {@code "TypeScript"})
     */
    protected final String subject() {
        return subject;
    }

    /**
     * The idle-queue borrow timeout, for use in subclass log messages.
     *
     * @return borrow timeout in milliseconds
     */
    protected final long borrowTimeoutMillis() {
        return BORROW_TIMEOUT_MILLIS;
    }

    /**
     * Borrows an idle worker, starting one on demand if the pool has not yet
     * reached {@code poolSize}, then waiting up to {@link #borrowTimeoutMillis()}.
     *
     * @param context creation context forwarded to a newly started worker
     * @return an idle worker, or {@code null} on timeout
     */
    @SuppressWarnings("PMD.DoNotUseThreads")
    protected final W borrow(C context) {
        startWorkerOnDemand(context);
        try {
            return idleWorkers.poll(BORROW_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Returns a worker to the idle queue.  A worker that cannot be re-enqueued
     * (which should not happen in a correctly sized pool) is killed.
     *
     * @param worker the worker to return
     */
    protected final void returnWorker(W worker) {
        if (!idleWorkers.offer(worker)) {
            kill(worker, "pool queue full on return");
        }
    }

    /**
     * Records a restart event in the circuit breaker and, if the circuit is
     * still closed, starts a replacement worker and puts it in the idle queue.
     *
     * @param context creation context for the replacement worker
     */
    protected final void replaceWorker(C context) {
        circuitBreaker.recordRestart();
        if (circuitBreaker.isOpen()) {
            return; // circuit just tripped; don't create more workers
        }
        workerCreationLock.lock();
        try {
            W replacement = createWorker(nextWorkerIndex.getAndIncrement(), context);
            if (!idleWorkers.offer(replacement)) {
                kill(replacement, "pool queue full after replacement");
            }
        } catch (IOException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Failed to start replacement " + subject + " worker", e);
            }
        } finally {
            workerCreationLock.unlock();
        }
    }

    /**
     * Shuts down all workers and removes the JVM shutdown hook.  Idempotent and
     * safe to call from any thread.
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
     * Starts one new worker and places it in the idle queue if the queue is
     * currently empty and the pool has not yet reached its maximum size.
     * Concurrent callers are serialised by {@link #workerCreationLock}; the
     * condition is rechecked inside the lock to avoid starting duplicate workers.
     */
    private void startWorkerOnDemand(C context) {
        if (!idleWorkers.isEmpty() || nextWorkerIndex.get() >= poolSize) {
            return; // fast path: worker already available or pool is full
        }
        workerCreationLock.lock();
        try {
            if (!idleWorkers.isEmpty() || nextWorkerIndex.get() >= poolSize) {
                return; // another thread already started one
            }
            W worker = createWorker(nextWorkerIndex.getAndIncrement(), context);
            if (!idleWorkers.offer(worker)) {
                if (LOG.isLoggable(Level.WARNING)) {
                    LOG.warning("Idle worker queue full; terminating newly created "
                            + subject + " worker.");
                }
                kill(worker, "idle queue full");
            }
        } catch (IOException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Failed to start " + subject
                        + " worker on demand (index " + nextWorkerIndex.get() + ")", e);
            }
        } finally {
            workerCreationLock.unlock();
        }
    }

    /** Drains the idle queue and kills all workers. */
    private void shutdownAllWorkers() {
        List<W> workers = new ArrayList<>(poolSize);
        idleWorkers.drainTo(workers);
        for (W w : workers) {
            kill(w, "pool shutdown");
        }
        if (LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO, "{0} worker pool shut down ({1} worker(s) stopped)",
                    new Object[] { subject, workers.size() });
        }
    }
}
