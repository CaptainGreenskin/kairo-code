package io.kairo.code.core.task;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BackgroundTaskNotificationQueueTest {

    @Test
    void offerAndPoll() throws InterruptedException {
        var queue = new BackgroundTaskNotificationQueue();
        assertTrue(queue.isEmpty());

        queue.offer("notification-1");
        assertFalse(queue.isEmpty());
        assertEquals(1, queue.size());

        String result = queue.poll(1, TimeUnit.SECONDS);
        assertEquals("notification-1", result);
        assertTrue(queue.isEmpty());
    }

    @Test
    void pollTimesOutWhenEmpty() throws InterruptedException {
        var queue = new BackgroundTaskNotificationQueue();
        String result = queue.poll(50, TimeUnit.MILLISECONDS);
        assertNull(result);
    }

    @Test
    void pollBlocksUntilOffer() throws InterruptedException {
        var queue = new BackgroundTaskNotificationQueue();
        var received = new AtomicReference<String>();
        var latch = new CountDownLatch(1);

        Thread.startVirtualThread(() -> {
            try {
                received.set(queue.poll(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        Thread.sleep(50);
        assertNull(received.get());

        queue.offer("async-notification");
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals("async-notification", received.get());
    }

    @Test
    void multipleNotificationsPreserveOrder() throws InterruptedException {
        var queue = new BackgroundTaskNotificationQueue();
        queue.offer("first");
        queue.offer("second");
        queue.offer("third");

        assertEquals(3, queue.size());
        assertEquals("first", queue.poll(1, TimeUnit.SECONDS));
        assertEquals("second", queue.poll(1, TimeUnit.SECONDS));
        assertEquals("third", queue.poll(1, TimeUnit.SECONDS));
        assertTrue(queue.isEmpty());
    }
}
