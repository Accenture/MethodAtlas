package org.egothor.methodatlas.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the generic {@link WorkerPool} lifecycle logic using an
 * in-process fake worker (no external processes).
 */
class WorkerPoolTest {

    /** Trivial in-process stand-in for a real worker process. */
    private static final class FakeWorker {
        private final int index;
        private volatile boolean killed;

        FakeWorker(int index) {
            this.index = index;
        }
    }

    /** Test double exposing the protected {@link WorkerPool} API. */
    private static final class FakePool extends WorkerPool<FakeWorker, Void> {
        private final List<FakeWorker> created = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger killCount = new AtomicInteger();
        private volatile boolean failCreate;

        FakePool(int poolSize, WorkerCircuitBreaker cb) {
            super("Fake", "fake-worker-pool-shutdown", poolSize, cb);
        }

        @Override
        protected FakeWorker createWorker(int index, Void context) throws IOException {
            if (failCreate) {
                throw new IOException("simulated worker start failure");
            }
            FakeWorker w = new FakeWorker(index);
            created.add(w);
            return w;
        }

        @Override
        protected void kill(FakeWorker worker, String reason) {
            worker.killed = true;
            killCount.incrementAndGet();
        }

        FakeWorker doBorrow() {
            return borrow(null);
        }

        void doReturn(FakeWorker w) {
            returnWorker(w);
        }

        void doReplace() {
            replaceWorker(null);
        }
    }

    private static WorkerCircuitBreaker cb(int maxRestarts) {
        return new WorkerCircuitBreaker("Fake", "fake", maxRestarts, 60);
    }

    @Test
    void borrow_startsWorkerOnDemand() {
        try (FakePool pool = new FakePool(2, cb(5))) {
            FakeWorker w = pool.doBorrow();
            assertNotNull(w, "first borrow should start a worker on demand");
            assertEquals(1, pool.created.size());
        }
    }

    @Test
    void borrowReturnBorrow_reusesSameWorker() {
        try (FakePool pool = new FakePool(1, cb(5))) {
            FakeWorker first = pool.doBorrow();
            pool.doReturn(first);
            FakeWorker second = pool.doBorrow();
            assertSame(first, second, "a returned worker should be reused");
            assertEquals(1, pool.created.size(), "no extra worker should be created");
        }
    }

    @Test
    void replaceWorker_whenCircuitClosed_createsReplacement() {
        try (FakePool pool = new FakePool(2, cb(5))) {
            pool.doReplace(); // records one restart (circuit stays closed) and enqueues a replacement
            FakeWorker replacement = pool.doBorrow();
            assertNotNull(replacement);
            assertEquals(1, pool.created.size());
        }
    }

    @Test
    void replaceWorker_whenCircuitTrips_createsNothing() {
        try (FakePool pool = new FakePool(2, cb(1))) { // trips on the first restart
            pool.doReplace();
            assertTrue(pool.isCircuitOpen(), "circuit should be open after the limit");
            assertEquals(0, pool.created.size(), "no replacement once the circuit is open");
        }
    }

    @Test
    void replaceWorker_createFailure_isSwallowed() {
        try (FakePool pool = new FakePool(2, cb(5))) {
            pool.failCreate = true;
            pool.doReplace(); // must not throw despite the createWorker IOException
            assertEquals(0, pool.created.size());
        }
    }

    @Test
    void close_killsIdleWorkersAndIsIdempotent() {
        FakePool pool = new FakePool(1, cb(5));
        FakeWorker w = pool.doBorrow();
        pool.doReturn(w);

        pool.close();
        assertTrue(w.killed, "idle worker should be killed on shutdown");
        assertEquals(1, pool.killCount.get());

        pool.close(); // idempotent
        assertEquals(1, pool.killCount.get(), "second close must not kill again");
    }

    @Test
    void returnWorker_whenQueueFull_killsSurplus() {
        try (FakePool pool = new FakePool(1, cb(5))) {
            FakeWorker w = pool.doBorrow(); // queue now empty, one worker outstanding
            pool.doReturn(w);               // back in the queue (capacity 1, now full)

            FakeWorker surplus = new FakeWorker(99);
            pool.doReturn(surplus);         // queue full → surplus must be killed
            assertTrue(surplus.killed);
            assertFalse(w.killed);
        }
    }
}
