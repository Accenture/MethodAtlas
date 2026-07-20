package org.egothor.methodatlas.util;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tracks consecutive worker-process restarts and trips an open circuit when the
 * restart rate exceeds safe operating limits.
 *
 * <p>
 * This class is shared by every discovery plugin that manages a pool of
 * long-lived external worker processes (currently the TypeScript/Node.js and
 * Python plugins).  The human-readable {@code subject} (e.g. {@code "TypeScript"}
 * or {@code "Python"}) and the configuration-property {@code prefix} (e.g.
 * {@code "typescript"} or {@code "python"}) parameterise the log messages so a
 * single implementation serves all plugins without behavioural drift.
 * </p>
 *
 * <h2>Policy</h2>
 *
 * <p>
 * The circuit opens when the number of worker restarts within the trailing
 * {@code windowSeconds} sliding window reaches or exceeds {@code maxRestarts}.
 * Once open, the circuit never closes — the plugin is disabled for the
 * remainder of the scan run.  Operators can increase the threshold via the
 * {@code <prefix>.maxConsecutiveRestarts} and {@code <prefix>.restartWindowSec}
 * configuration properties.
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
public final class WorkerCircuitBreaker {

    private static final Logger LOG = Logger.getLogger(WorkerCircuitBreaker.class.getName());

    private final String subject;
    private final String configPrefix;
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
     * Creates a circuit breaker with the given limits and the system UTC clock.
     *
     * @param subject       human-readable plugin label used in log messages
     *                      (e.g. {@code "TypeScript"}); never {@code null}
     * @param configPrefix  configuration-property prefix named in the remediation
     *                      hint (e.g. {@code "typescript"}); never {@code null}
     * @param maxRestarts   maximum number of restarts allowed within the window
     *                      before the circuit opens; must be positive
     * @param windowSeconds width of the sliding time window in seconds;
     *                      must be positive
     * @throws IllegalArgumentException if either numeric argument is not positive
     */
    public WorkerCircuitBreaker(String subject, String configPrefix,
            int maxRestarts, int windowSeconds) {
        this(subject, configPrefix, maxRestarts, windowSeconds, Clock.systemUTC());
    }

    /**
     * Creates a circuit breaker with a custom clock for testing.
     *
     * @param subject       human-readable plugin label used in log messages
     * @param configPrefix  configuration-property prefix named in the remediation hint
     * @param maxRestarts   maximum restarts within the window
     * @param windowSeconds sliding window width in seconds
     * @param clock         clock used to determine current time
     * @throws IllegalArgumentException if either numeric argument is not positive
     */
    public WorkerCircuitBreaker(String subject, String configPrefix,
            int maxRestarts, int windowSeconds, Clock clock) {
        if (maxRestarts <= 0) {
            throw new IllegalArgumentException("maxRestarts must be positive, got: " + maxRestarts);
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds must be positive, got: " + windowSeconds);
        }
        this.subject = subject;
        this.configPrefix = configPrefix;
        this.maxRestarts = maxRestarts;
        this.window = Duration.ofSeconds(windowSeconds);
        this.clock = clock;
    }

    /**
     * Returns {@code true} when the circuit is open (plugin must be disabled).
     *
     * @return {@code true} if the circuit has tripped
     */
    public boolean isOpen() {
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
    public void recordRestart() {
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
                    LOG.warning(subject + " worker pool circuit breaker TRIPPED — "
                            + restartTimestamps.size() + " restarts within the last "
                            + window.toSeconds() + " s (limit=" + maxRestarts + "). "
                            + subject + " scanning is disabled for the remainder of this run. "
                            + "Investigate worker logs for the root cause; "
                            + "increase " + configPrefix + ".maxConsecutiveRestarts or "
                            + configPrefix + ".restartWindowSec to raise the threshold.");
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
    public int restartsInWindow() {
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
