package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 10 tests for PriorityRequestQueue — verifies MAX-HEAP ordering.
 * Bug 3 (inverted sift-up) should cause priority-order tests to fail.
 */
class PriorityRequestQueueTest {

    private PriorityRequestQueue queue;

    @BeforeEach
    void setUp() {
        queue = new PriorityRequestQueue();
    }

    @Test
    void shouldStartEmpty() {
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
    }

    @Test
    void shouldReturnNullPeekOnEmpty() {
        assertNull(queue.peek());
    }

    @Test
    void shouldThrowOnDequeueFromEmpty() {
        assertThrows(java.util.NoSuchElementException.class, queue::dequeue);
    }

    @Test
    void shouldEnqueueAndDequeueSingleRequest() {
        Request r = new Request("1", "c1", Request.Priority.HIGH, Instant.now(), "payload");
        queue.enqueue(r);
        assertEquals(1, queue.size());
        assertSame(r, queue.dequeue());
        assertTrue(queue.isEmpty());
    }

    @Test
    void shouldDequeueHighBeforeMedium() {
        Request medium = new Request("1", "c1", Request.Priority.MEDIUM, Instant.now(), "m");
        Request high = new Request("2", "c1", Request.Priority.HIGH, Instant.now(), "h");

        queue.enqueue(medium);
        queue.enqueue(high);

        assertEquals(Request.Priority.HIGH, queue.dequeue().getPriority());
        assertEquals(Request.Priority.MEDIUM, queue.dequeue().getPriority());
    }

    @Test
    void shouldDequeueHighBeforeLow() {
        Request low = new Request("1", "c1", Request.Priority.LOW, Instant.now(), "l");
        Request high = new Request("2", "c1", Request.Priority.HIGH, Instant.now(), "h");

        queue.enqueue(low);
        queue.enqueue(high);

        assertEquals(Request.Priority.HIGH, queue.dequeue().getPriority());
        assertEquals(Request.Priority.LOW, queue.dequeue().getPriority());
    }

    @Test
    void shouldDequeueMediumBeforeLow() {
        Request low = new Request("1", "c1", Request.Priority.LOW, Instant.now(), "l");
        Request medium = new Request("2", "c1", Request.Priority.MEDIUM, Instant.now(), "m");

        queue.enqueue(low);
        queue.enqueue(medium);

        assertEquals(Request.Priority.MEDIUM, queue.dequeue().getPriority());
        assertEquals(Request.Priority.LOW, queue.dequeue().getPriority());
    }

    @Test
    void shouldDequeueAllPrioritiesInCorrectOrder() {
        Request low1 = new Request("1", "c1", Request.Priority.LOW, Instant.now(), "l1");
        Request high1 = new Request("2", "c1", Request.Priority.HIGH, Instant.now(), "h1");
        Request med1 = new Request("3", "c1", Request.Priority.MEDIUM, Instant.now(), "m1");
        Request high2 = new Request("4", "c1", Request.Priority.HIGH, Instant.now(), "h2");
        Request low2 = new Request("5", "c1", Request.Priority.LOW, Instant.now(), "l2");

        queue.enqueue(low1);
        queue.enqueue(high1);
        queue.enqueue(med1);
        queue.enqueue(high2);
        queue.enqueue(low2);

        assertEquals(Request.Priority.HIGH, queue.dequeue().getPriority());
        assertEquals(Request.Priority.HIGH, queue.dequeue().getPriority());
        assertEquals(Request.Priority.MEDIUM, queue.dequeue().getPriority());
        assertEquals(Request.Priority.LOW, queue.dequeue().getPriority());
        assertEquals(Request.Priority.LOW, queue.dequeue().getPriority());
    }

    @Test
    void peekShouldReturnHighestPriorityWithoutRemoving() {
        Request low = new Request("1", "c1", Request.Priority.LOW, Instant.now(), "l");
        Request high = new Request("2", "c1", Request.Priority.HIGH, Instant.now(), "h");

        queue.enqueue(low);
        queue.enqueue(high);

        assertSame(high, queue.peek());
        assertEquals(2, queue.size());
        assertSame(high, queue.peek());
    }

    @Test
    void shouldHandleSamePriorityRequests() {
        Request r1 = new Request("1", "c1", Request.Priority.MEDIUM, Instant.now(), "a");
        Request r2 = new Request("2", "c1", Request.Priority.MEDIUM, Instant.now(), "b");
        Request r3 = new Request("3", "c1", Request.Priority.MEDIUM, Instant.now(), "c");

        queue.enqueue(r1);
        queue.enqueue(r2);
        queue.enqueue(r3);

        // All same priority — any order is acceptable as long as all three are returned
        assertEquals(3, queue.size());
        assertNotNull(queue.dequeue());
        assertNotNull(queue.dequeue());
        assertNotNull(queue.dequeue());
        assertTrue(queue.isEmpty());
    }
}
