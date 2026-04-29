package org.egothor.methodatlas.discovery.typescript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tracks consecutive worker-process restarts and trips an open circuit when
 * the restart rate exceeds safe operating limits.
 *
 * <h2>Policy</h2>
 *
 * <p>
 * The circuit opens when the number of worker restarts within the trailing
 * {@code windowSeconds} sliding window reaches or exceeds
 * {@code maxRestarts}.  Once open, the circuit never closes — the plugin is
 * disabled for the remainder of the scan run.  Operators can increase the
 * threshold via the {@code typescript.maxConsecutiveRestarts} and
 * {@code typescript.restartWindowSec} configuration properties.
 * </p>
 *
 * <h2>Rationale</h2>
 *
 * <p>
 * Without a circuit breaker, a buggy worker or a systematically
 * parse-error-inducing input file would cause infinite restart loops,
 * consuming system resources and masking the root cause.  The circuit breaker
 * bounds the impact: after a configurable number of failures it emits a clear
 * {@code WARNING} and stops attempting recovery.
 * </p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * All mutating methods are guarded by a {@link ReentrantLock}.  The circuit
 * breaker is shared across the worker pool, which may service requests from a
 * single thread but could in future be extended to multiple scan threads.
 * </p>
 */
final class WorkerCircuitBreaker {

    private static final Logger LOG = Logger.getLogger(WorkerCircuitBreaker.class.getName());

    private final int maxRestarts;
    private final Duration window;
    private final Clock clock;

    /** Guards all mutable state in this class. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Timestamps of recent restart events within the sliding window. */
    private final Deque<Instant> restartTimestamps = new ArrayDeque<>();

    /** Whether the circuit is currently open (plugin disabled). */
    private boolean open;

    /**
     * Creates a circuit breaker with the given limits.
     *
     * @param maxRestarts    maximum number of restarts allowed within the window
     *                       before the circuit opens; must be positive
     * @param windowSeconds  width of the sliding time window in seconds;
     *                       must be positive
     * @throws IllegalArgumentException if either argument is not positive
     */
    /* default */ WorkerCircuitBreaker(int maxRestarts, int windowSeconds) {
        this(maxRestarts, windowSeconds, Clock.systemUTC());
    }

    /**
     * Creates a circuit breaker with a custom clock for testing.
     *
     * @param maxRestarts    maximum restarts within the window
     * @param windowSeconds  sliding window width in seconds
     * @param clock          clock used to determine current time
     */
    /* default */ WorkerCircuitBreaker(int maxRestarts, int windowSeconds, Clock clock) {
        if (maxRestarts <= 0) {
            throw new IllegalArgumentException("maxRestarts must be positive, got: " + maxRestarts);
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds must be positive, got: " + windowSeconds);
        }
        this.maxRestarts = maxRestarts;
        this.window = Duration.ofSeconds(windowSeconds);
        this.clock = clock;
    }

    /**
     * Returns {@code true} when the circuit is open (plugin must be disabled).
     *
     * @return {@code true} if the circuit has tripped
     */
    /* default */ boolean isOpen() {
        lock.lock();
        try {
            return open;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records a worker restart event and opens the circuit if the restart
     * threshold has been exceeded within the sliding window.
     *
     * <p>
     * When the circuit trips, a {@code WARNING} log line is emitted once.
     * Subsequent calls to {@link #recordRestart()} are silently ignored.
     * </p>
     */
    /* default */ void recordRestart() {
        lock.lock();
        try {
            if (open) {
                return; // already open — no further action needed
            }

            Instant now = clock.instant();
            Instant windowStart = now.minus(window);

            // Evict restart records that have fallen outside the window.
            while (!restartTimestamps.isEmpty() && restartTimestamps.peekFirst().isBefore(windowStart)) {
                restartTimestamps.pollFirst();
            }

            restartTimestamps.addLast(now);

            if (restartTimestamps.size() >= maxRestarts) {
                open = true;
                if (LOG.isLoggable(Level.WARNING)) {
                    LOG.warning("TypeScript worker pool circuit breaker TRIPPED — "
                            + restartTimestamps.size() + " restarts within the last "
                            + window.toSeconds() + " s (limit=" + maxRestarts + "). "
                            + "TypeScript scanning is disabled for the remainder of this run. "
                            + "Investigate worker logs for the root cause; "
                            + "increase typescript.maxConsecutiveRestarts or "
                            + "typescript.restartWindowSec to raise the threshold.");
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of restart events currently recorded within the
     * sliding window.  Useful for monitoring and testing.
     *
     * @return restart count within the active window
     */
    /* default */ int restartsInWindow() {
        lock.lock();
        try {
            if (open) {
                return restartTimestamps.size();
            }
            Instant now = clock.instant();
            Instant windowStart = now.minus(window);
            // Count without evicting (read-only view).
            return (int) restartTimestamps.stream()
                    .filter(t -> !t.isBefore(windowStart))
                    .count();
        } finally {
            lock.unlock();
        }
    }
}
