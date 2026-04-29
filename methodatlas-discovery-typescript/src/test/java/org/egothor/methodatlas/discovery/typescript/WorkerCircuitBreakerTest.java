package org.egothor.methodatlas.discovery.typescript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkerCircuitBreaker}.
 */
class WorkerCircuitBreakerTest {

    // -------------------------------------------------------------------------
    // Constructor validation
    // -------------------------------------------------------------------------

    @Test
    void constructor_zeroMaxRestarts_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkerCircuitBreaker(0, 60));
    }

    @Test
    void constructor_negativeWindow_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkerCircuitBreaker(5, -1));
    }

    // -------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------

    @Test
    void initialState_circuitIsClosed() {
        WorkerCircuitBreaker cb = new WorkerCircuitBreaker(3, 60);
        assertFalse(cb.isOpen());
    }

    @Test
    void initialState_restartsInWindowIsZero() {
        WorkerCircuitBreaker cb = new WorkerCircuitBreaker(3, 60);
        assertEquals(0, cb.restartsInWindow());
    }

    // -------------------------------------------------------------------------
    // Circuit does not trip before limit
    // -------------------------------------------------------------------------

    @Test
    void recordRestart_belowLimit_circuitRemainsOpen() {
        WorkerCircuitBreaker cb = new WorkerCircuitBreaker(5, 60);
        cb.recordRestart();
        cb.recordRestart();
        cb.recordRestart();
        cb.recordRestart();
        assertFalse(cb.isOpen(), "Circuit should stay closed below the limit");
        assertEquals(4, cb.restartsInWindow());
    }

    // -------------------------------------------------------------------------
    // Circuit trips at limit
    // -------------------------------------------------------------------------

    @Test
    void recordRestart_atLimit_tripsCircuit() {
        WorkerCircuitBreaker cb = new WorkerCircuitBreaker(3, 60);
        cb.recordRestart();
        cb.recordRestart();
        assertFalse(cb.isOpen());
        cb.recordRestart(); // third restart — should trip
        assertTrue(cb.isOpen(), "Circuit should open at the restart limit");
    }

    // -------------------------------------------------------------------------
    // Events outside the window are evicted
    // -------------------------------------------------------------------------

    @Test
    void restartsOutsideWindow_areEvicted() {
        // Use a controllable clock backed by an AtomicLong so we can advance time.
        AtomicLong epochSeconds = new AtomicLong(0L);
        Clock controllable = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() {
                return Instant.ofEpochSecond(epochSeconds.get());
            }
        };

        WorkerCircuitBreaker cb = new WorkerCircuitBreaker(3, 60, controllable);

        // Record two restarts at t=0.
        cb.recordRestart();
        cb.recordRestart();
        assertEquals(2, cb.restartsInWindow());

        // Advance time past the window boundary.
        epochSeconds.set(61);

        // The two old events are now outside the window.
        assertEquals(0, cb.restartsInWindow());
        assertFalse(cb.isOpen());
    }

    // -------------------------------------------------------------------------
    // Idempotency after trip
    // -------------------------------------------------------------------------

    @Test
    void recordRestart_afterTrip_isIdempotent() {
        WorkerCircuitBreaker cb = new WorkerCircuitBreaker(2, 60);
        cb.recordRestart();
        cb.recordRestart();
        assertTrue(cb.isOpen());

        // Calling again after trip must not throw.
        cb.recordRestart();
        assertTrue(cb.isOpen());
    }
}
