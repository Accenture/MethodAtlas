package org.egothor.methodatlas.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the shared {@link WorkerCircuitBreaker}.
 *
 * <p>
 * This single suite covers the circuit-breaker behaviour relied upon by every
 * process-worker plugin (TypeScript and Python), replacing the per-plugin
 * copies and closing the Python coverage gap noted in the audit.
 * </p>
 */
class WorkerCircuitBreakerTest {

    private static WorkerCircuitBreaker breaker(int maxRestarts, int windowSeconds) {
        return new WorkerCircuitBreaker("TypeScript", "typescript", maxRestarts, windowSeconds);
    }

    private static WorkerCircuitBreaker breaker(int maxRestarts, int windowSeconds, Clock clock) {
        return new WorkerCircuitBreaker("Python", "python", maxRestarts, windowSeconds, clock);
    }

    @Test
    void constructor_zeroMaxRestarts_throws() {
        assertThrows(IllegalArgumentException.class, () -> breaker(0, 60));
    }

    @Test
    void constructor_negativeWindow_throws() {
        assertThrows(IllegalArgumentException.class, () -> breaker(5, -1));
    }

    @Test
    void initialState_circuitIsClosed() {
        assertFalse(breaker(3, 60).isOpen());
    }

    @Test
    void initialState_restartsInWindowIsZero() {
        assertEquals(0, breaker(3, 60).restartsInWindow());
    }

    @Test
    void recordRestart_belowLimit_circuitRemainsClosed() {
        WorkerCircuitBreaker cb = breaker(5, 60);
        cb.recordRestart();
        cb.recordRestart();
        cb.recordRestart();
        cb.recordRestart();
        assertFalse(cb.isOpen(), "Circuit should stay closed below the limit");
        assertEquals(4, cb.restartsInWindow());
    }

    @Test
    void recordRestart_atLimit_tripsCircuit() {
        WorkerCircuitBreaker cb = breaker(3, 60);
        cb.recordRestart();
        cb.recordRestart();
        assertFalse(cb.isOpen());
        cb.recordRestart(); // third restart — should trip
        assertTrue(cb.isOpen(), "Circuit should open at the restart limit");
    }

    @Test
    void restartsOutsideWindow_areEvicted() {
        AtomicLong epochSeconds = new AtomicLong(0L);
        Clock controllable = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() {
                return Instant.ofEpochSecond(epochSeconds.get());
            }
        };

        WorkerCircuitBreaker cb = breaker(3, 60, controllable);
        cb.recordRestart();
        cb.recordRestart();
        assertEquals(2, cb.restartsInWindow());

        epochSeconds.set(61);
        assertEquals(0, cb.restartsInWindow());
        assertFalse(cb.isOpen());
    }

    @Test
    void recordRestart_afterTrip_isIdempotent() {
        WorkerCircuitBreaker cb = breaker(2, 60);
        cb.recordRestart();
        cb.recordRestart();
        assertTrue(cb.isOpen());

        cb.recordRestart(); // must not throw
        assertTrue(cb.isOpen());
        assertEquals(2, cb.restartsInWindow());
    }
}
