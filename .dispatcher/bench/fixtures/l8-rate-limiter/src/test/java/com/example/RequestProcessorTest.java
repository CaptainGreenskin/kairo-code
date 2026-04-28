package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 15 tests for RequestProcessor — rate limiting + statistics.
 * Bug 4 (double-counting) and Bug 5 (record-before-check) should cause failures.
 */
class RequestProcessorTest {

    private RateLimiter rateLimiter;
    private PriorityRequestQueue queue;
    private RequestProcessor processor;

    @BeforeEach
    void setUp() {
        rateLimiter = new RateLimiter(3, 1000);
        queue = new PriorityRequestQueue();
        processor = new RequestProcessor(rateLimiter, queue);
    }

    private Request makeRequest(String id, String clientId, Request.Priority priority) {
        return new Request(id, clientId, priority, Instant.now(), "payload-" + id);
    }

    // --- Basic processing ---

    @Test
    void shouldProcessFirstRequestSuccessfully() {
        Request r = makeRequest("r1", "c1", Request.Priority.HIGH);
        String result = processor.processRequest(r);
        assertTrue(result.startsWith("PROCESSED:"));
    }

    @Test
    void shouldIncrementTotalCountOnSuccess() {
        processor.processRequest(makeRequest("r1", "c1", Request.Priority.HIGH));
        assertEquals(1, processor.getTotalCount());
    }

    @Test
    void shouldIncrementSuccessCountOnSuccess() {
        processor.processRequest(makeRequest("r1", "c1", Request.Priority.HIGH));
        assertEquals(1, processor.getSuccessCount());
    }

    @Test
    void shouldNotDoubleCountSuccess() {
        // Bug 4: successCount gets incremented twice per success
        processor.processRequest(makeRequest("r1", "c1", Request.Priority.HIGH));
        processor.processRequest(makeRequest("r2", "c1", Request.Priority.HIGH));
        assertEquals(2, processor.getSuccessCount(), "Each success should count exactly once");
    }

    @Test
    void totalCountShouldEqualSuccessPlusRejected() {
        // Process 3 requests — all within limit
        for (int i = 1; i <= 3; i++) {
            processor.processRequest(makeRequest("r" + i, "c1", Request.Priority.HIGH));
        }
        assertEquals(3, processor.getTotalCount());
        assertEquals(3, processor.getSuccessCount());
        assertEquals(0, processor.getRejectedCount());
        assertEquals(processor.getSuccessCount() + processor.getRejectedCount(), processor.getTotalCount());
    }

    // --- Rate limiting ---

    @Test
    void shouldRejectRequestsBeyondRateLimit() {
        // Limit is 3 per window
        for (int i = 1; i <= 3; i++) {
            processor.processRequest(makeRequest("r" + i, "c1", Request.Priority.HIGH));
        }
        Request over = makeRequest("r4", "c1", Request.Priority.HIGH);
        String result = processor.processRequest(over);
        assertTrue(result.startsWith("REJECTED:"), "Should be rejected: " + result);
    }

    @Test
    void shouldIncrementRejectedCountOnRejection() {
        for (int i = 1; i <= 3; i++) {
            processor.processRequest(makeRequest("r" + i, "c1", Request.Priority.HIGH));
        }
        processor.processRequest(makeRequest("r4", "c1", Request.Priority.HIGH));
        assertEquals(1, processor.getRejectedCount());
    }

    @Test
    void shouldTrackCorrectStatsAfterMixedResults() {
        // 2 succeed, 1 rejected (limit is 3)
        processor.processRequest(makeRequest("r1", "c1", Request.Priority.HIGH));
        processor.processRequest(makeRequest("r2", "c1", Request.Priority.HIGH));
        processor.processRequest(makeRequest("r3", "c1", Request.Priority.HIGH));
        processor.processRequest(makeRequest("r4", "c1", Request.Priority.HIGH));

        // Bug 5: because record happens before check, the rejected request still consumes quota.
        // With the bug: total=4, success=3, rejected=1, but the rate limiter sees 4 recorded requests.
        // After fix: total=4, success=3, rejected=1 — but the rate limiter sees exactly 3 recorded before rejection.
        assertEquals(4, processor.getTotalCount());
        assertEquals(3, processor.getSuccessCount());
        assertEquals(1, processor.getRejectedCount());
    }

    @Test
    void totalCountShouldAlwaysEqualSuccessPlusRejected() {
        for (int i = 1; i <= 5; i++) {
            processor.processRequest(makeRequest("r" + i, "c1", Request.Priority.HIGH));
        }
        assertEquals(
                processor.getSuccessCount() + processor.getRejectedCount(),
                processor.getTotalCount(),
                "totalCount must equal successCount + rejectedCount"
        );
    }

    @Test
    void differentClientsShouldHaveIndependentLimits() {
        // client-1 uses up its limit
        for (int i = 1; i <= 3; i++) {
            processor.processRequest(makeRequest("r" + i, "c1", Request.Priority.HIGH));
        }
        // client-2 should still be allowed
        String result = processor.processRequest(makeRequest("r4", "c2", Request.Priority.HIGH));
        assertTrue(result.startsWith("PROCESSED:"));
    }

    // --- Reset ---

    @Test
    void resetStatsShouldClearAllCounters() {
        processor.processRequest(makeRequest("r1", "c1", Request.Priority.HIGH));
        processor.resetStats();
        assertEquals(0, processor.getTotalCount());
        assertEquals(0, processor.getSuccessCount());
        assertEquals(0, processor.getRejectedCount());
    }

    // --- Priority queue integration ---

    @Test
    void shouldReturnProcessedResultWithRequestId() {
        Request r = makeRequest("test-123", "c1", Request.Priority.MEDIUM);
        String result = processor.processRequest(r);
        assertTrue(result.contains("test-123"));
    }

    @Test
    void shouldReturnRejectedResultWithClientId() {
        for (int i = 1; i <= 3; i++) {
            processor.processRequest(makeRequest("r" + i, "c1", Request.Priority.HIGH));
        }
        String result = processor.processRequest(makeRequest("r4", "c1", Request.Priority.HIGH));
        assertTrue(result.contains("c1"));
    }

    @Test
    void shouldExposeRateLimiterAndQueue() {
        assertSame(rateLimiter, processor.getRateLimiter());
        assertSame(queue, processor.getQueue());
    }

    @Test
    void shouldHandleRequestWithNullPayload() {
        Request r = new Request("r1", "c1", Request.Priority.LOW, Instant.now(), null);
        String result = processor.processRequest(r);
        assertTrue(result.startsWith("PROCESSED:"));
    }
}
