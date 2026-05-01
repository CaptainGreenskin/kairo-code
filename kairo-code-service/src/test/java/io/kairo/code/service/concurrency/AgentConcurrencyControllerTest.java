package io.kairo.code.service.concurrency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConcurrencyControllerTest {

    private AgentConcurrencyController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentConcurrencyController();
    }

    @Test
    void basicAcquireAndRelease() {
        try (AgentSlot slot = controller.acquire("s1")) {
            assertEquals(1, controller.globalActiveCount());
            assertEquals(1, controller.sessionActiveCount("s1"));
        }
        assertEquals(0, controller.globalActiveCount());
        assertEquals(0, controller.sessionActiveCount("s1"));
    }

    @Test
    void depthLimitEnforced() {
        // depth 1
        try (AgentSlot s1 = controller.acquire("d1")) {
            assertEquals(1, controller.currentDepth());
            // depth 2
            try (AgentSlot s2 = controller.acquire("d1")) {
                assertEquals(2, controller.currentDepth());
                // depth 3
                try (AgentSlot s3 = controller.acquire("d1")) {
                    assertEquals(3, controller.currentDepth());
                    // depth 4 should fail
                    AgentConcurrencyException ex = assertThrows(
                            AgentConcurrencyException.class,
                            () -> controller.acquire("d1"));
                    assertEquals(AgentConcurrencyException.Reason.DEPTH_LIMIT, ex.reason());
                }
            }
        }
        assertEquals(0, controller.currentDepth());
    }

    @Test
    void slotIdempotentClose() {
        AgentSlot slot = controller.acquire("s2");
        slot.close();
        slot.close(); // second close should be no-op, not throw
        assertEquals(0, controller.globalActiveCount());
    }

    @Test
    void concurrentAcquireWithinLimits() throws Exception {
        int threads = 5;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Exception> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            String sid = "session-" + i;
            new Thread(() -> {
                try (AgentSlot slot = controller.acquire(sid)) {
                    ready.countDown();
                    ready.await();
                    Thread.sleep(50);
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        done.await(5, TimeUnit.SECONDS);
        assertTrue(errors.isEmpty(), "No concurrent errors expected: " + errors);
        assertEquals(0, controller.globalActiveCount());
    }

    @Test
    void sessionLimitExceeded() throws Exception {
        // Each thread gets its own ThreadLocal depth, so we can test session limit independently.
        int sessionMax = 10;
        int threads = sessionMax + 1;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<AgentSlot> slots = new CopyOnWriteArrayList<>();
        AtomicReference<AgentConcurrencyException> overflowError = new AtomicReference<>();

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    AgentSlot slot = controller.acquire("limited-session");
                    slots.add(slot);
                    ready.countDown();
                    ready.await();
                    Thread.sleep(50);
                    slot.close();
                } catch (AgentConcurrencyException e) {
                    overflowError.set(e);
                    ready.countDown();
                } catch (Exception e) {
                    overflowError.set(new AgentConcurrencyException(
                            AgentConcurrencyException.Reason.SESSION_LIMIT, e.getMessage()));
                    ready.countDown();
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(5, TimeUnit.SECONDS);
        exec.shutdown();

        assertEquals(sessionMax, slots.size(), "Expected " + sessionMax + " successful acquires");
        assertTrue(overflowError.get() != null, "Expected an overflow exception");
        assertEquals(AgentConcurrencyException.Reason.SESSION_LIMIT, overflowError.get().reason());

        assertEquals(0, controller.sessionActiveCount("limited-session"));
    }

    @Test
    void globalLimitExceeded() throws Exception {
        // Each thread gets its own ThreadLocal depth, so we can test global limit independently.
        int globalMax = 30;
        int threads = globalMax + 1;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<AgentSlot> slots = new CopyOnWriteArrayList<>();
        AtomicReference<AgentConcurrencyException> overflowError = new AtomicReference<>();

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final String sid = "global-session-" + i;
            exec.submit(() -> {
                try {
                    AgentSlot slot = controller.acquire(sid);
                    slots.add(slot);
                    ready.countDown();
                    ready.await();
                    Thread.sleep(50);
                    slot.close();
                } catch (AgentConcurrencyException e) {
                    overflowError.set(e);
                    ready.countDown();
                } catch (Exception e) {
                    overflowError.set(new AgentConcurrencyException(
                            AgentConcurrencyException.Reason.GLOBAL_LIMIT, e.getMessage()));
                    ready.countDown();
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(10, TimeUnit.SECONDS);
        exec.shutdown();

        assertEquals(globalMax, slots.size(), "Expected " + globalMax + " successful acquires");
        assertTrue(overflowError.get() != null, "Expected an overflow exception");
        assertEquals(AgentConcurrencyException.Reason.GLOBAL_LIMIT, overflowError.get().reason());

        assertEquals(0, controller.globalActiveCount());
    }

    @Test
    void multipleSessionsTrackIndependently() {
        try (AgentSlot s1 = controller.acquire("session-a")) {
            try (AgentSlot s2 = controller.acquire("session-b")) {
                assertEquals(2, controller.globalActiveCount());
                assertEquals(1, controller.sessionActiveCount("session-a"));
                assertEquals(1, controller.sessionActiveCount("session-b"));
            }
            assertEquals(1, controller.globalActiveCount());
            assertEquals(0, controller.sessionActiveCount("session-b"));
        }
        assertEquals(0, controller.globalActiveCount());
    }

    @Test
    void depthResetsAfterFullRelease() {
        try (AgentSlot s1 = controller.acquire("depth-test")) {
            assertEquals(1, controller.currentDepth());
        }
        assertEquals(0, controller.currentDepth());

        // After full release, depth should reset and we can acquire again
        try (AgentSlot s2 = controller.acquire("depth-test")) {
            assertEquals(1, controller.currentDepth());
        }
    }
}
