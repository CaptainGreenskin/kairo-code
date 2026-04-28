package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 12 single-threaded tests for RateLimiter sliding window logic.
 * Bug 2 (inverted removeIf) should cause several of these to fail.
 */
class RateLimiterTest {

    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        // 5 requests per 1000ms window
        limiter = new RateLimiter(5, 1000);
    }

    @Test
    void shouldAllowFirstRequest() {
        assertTrue(limiter.allowAndRecord("client-1"));
    }

    @Test
    void shouldAllowUpToMaxRequests() {
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.allowAndRecord("client-1"), "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void shouldRejectSixthRequest() {
        for (int i = 0; i < 5; i++) {
            limiter.allowAndRecord("client-1");
        }
        assertFalse(limiter.allowAndRecord("client-1"), "6th request should be rejected");
    }

    @Test
    void shouldTrackDifferentClientsIndependently() {
        for (int i = 0; i < 5; i++) {
            limiter.allowAndRecord("client-1");
        }
        // client-2 should still be allowed
        assertTrue(limiter.allowAndRecord("client-2"));
    }

    @Test
    void shouldReturnCorrectWindowedCount() {
        for (int i = 0; i < 3; i++) {
            limiter.recordRequest("client-1");
        }
        // After bug-2 fix, count should be 3
        // With the bug, the removeIf removes timestamps INSIDE the window, returning 0
        assertEquals(3, limiter.getWindowedCount("client-1"));
    }

    @Test
    void shouldReturnZeroForUnknownClient() {
        assertEquals(0, limiter.getWindowedCount("unknown"));
    }

    @Test
    void isAllowedShouldReflectCurrentCount() {
        limiter.recordRequest("client-1");
        // 1 out of 5 — still allowed
        assertTrue(limiter.isAllowed("client-1"));
    }

    @Test
    void isAllowedShouldBeFalseAtLimit() {
        for (int i = 0; i < 5; i++) {
            limiter.recordRequest("client-1");
        }
        // 5 out of 5 — at limit, still allowed (<=)
        assertTrue(limiter.isAllowed("client-1"));

        limiter.recordRequest("client-1");
        // 6 out of 5 — over limit
        assertFalse(limiter.isAllowed("client-1"));
    }

    @Test
    void recordRequestShouldBeIdempotentForTimestamp() {
        limiter.recordRequest("client-1");
        limiter.recordRequest("client-1");
        assertEquals(2, limiter.getWindowedCount("client-1"));
    }

    @Test
    void shouldExposeConfiguration() {
        assertEquals(5, limiter.getMaxRequestsPerWindow());
        assertEquals(1000, limiter.getWindowSizeMs());
    }

    @Test
    void multipleClientsShouldNotInterfere() {
        for (int i = 0; i < 5; i++) {
            limiter.allowAndRecord("A");
        }
        for (int i = 0; i < 3; i++) {
            limiter.allowAndRecord("B");
        }
        assertEquals(5, limiter.getWindowedCount("A"));
        assertEquals(3, limiter.getWindowedCount("B"));
    }

    @Test
    void allowAndRecordShouldReturnFalseWhenAtLimit() {
        for (int i = 0; i < 5; i++) {
            limiter.allowAndRecord("client-1");
        }
        assertFalse(limiter.allowAndRecord("client-1"));
    }
}
