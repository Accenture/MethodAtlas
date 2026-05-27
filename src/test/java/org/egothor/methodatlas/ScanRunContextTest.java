// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ScanRunContext}.
 *
 * <p>
 * Covers the set / get / clear lifecycle and the thread-local isolation
 * guarantee that lets concurrent runs (rare in the CLI, but possible when
 * MethodAtlas is invoked programmatically) keep their correlation ids
 * separate.
 * </p>
 *
 * @since 1.0.0
 */
class ScanRunContextTest {

    @AfterEach
    void clearContext() {
        ScanRunContext.clear();
    }

    @Test
    void current_whenNothingSet_returnsEmpty() {
        assertFalse(ScanRunContext.current().isPresent());
    }

    @Test
    void set_thenCurrent_returnsTheStoredValue() {
        ScanRun run = ScanRun.create("dev", "config");
        ScanRunContext.set(run);

        Optional<ScanRun> read = ScanRunContext.current();

        assertTrue(read.isPresent());
        assertSame(run, read.get());
    }

    @Test
    void set_nullArgument_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> ScanRunContext.set(null));
    }

    @Test
    void clear_afterSet_returnsToEmpty() {
        ScanRunContext.set(ScanRun.create("dev", "config"));
        ScanRunContext.clear();

        assertFalse(ScanRunContext.current().isPresent());
    }

    @Test
    void threadLocal_eachThreadSeesItsOwnValue() throws InterruptedException {
        ScanRun mainRun = ScanRun.create("dev", "main");
        ScanRunContext.set(mainRun);

        AtomicReference<Optional<ScanRun>> backgroundView = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            // Background thread has its own thread-local; it must see empty
            // even though the main thread has a value set.
            backgroundView.set(ScanRunContext.current());
            done.countDown();
        });
        t.start();
        done.await();

        assertFalse(backgroundView.get().isPresent(),
                "Thread-local isolation: background thread must not see "
                        + "the main thread's scan run");
        // The main thread's value is still set.
        assertEquals(mainRun, ScanRunContext.current().orElseThrow());
    }
}
