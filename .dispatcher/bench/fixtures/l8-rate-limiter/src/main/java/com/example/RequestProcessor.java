package com.example;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes requests through the priority queue with rate limiting and statistics.
 *
 * BUG 4 (logic): successCount is incremented twice on success — once in the try block
 *   and once in the finally block — causing double-counting.
 *
 * BUG 5 (logic): rate limiter record/check order is wrong — the request is recorded
 *   (consuming quota) BEFORE checking whether it is allowed, so the first request
 *   that exceeds the limit still gets counted as a recorded request.
 */
public class RequestProcessor {

    private final RateLimiter rateLimiter;
    private final PriorityRequestQueue queue;

    private final AtomicInteger totalCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger rejectedCount = new AtomicInteger(0);

    public RequestProcessor(RateLimiter rateLimiter, PriorityRequestQueue queue) {
        this.rateLimiter = rateLimiter;
        this.queue = queue;
    }

    /**
     * Process a single request — check rate limit, handle, and update statistics.
     *
     * Bug 5: records the request BEFORE checking the limit.
     *   Should check first, then record only if allowed.
     */
    public String processRequest(Request request) {
        String clientId = request.getClientId();

        // Bug 5: record first, then check — wrong order
        rateLimiter.recordRequest(clientId);
        if (rateLimiter.isAllowed(clientId)) {
            return handleAndCount(request);
        } else {
            rejectedCount.incrementAndGet();
            totalCount.incrementAndGet();
            return "REJECTED: rate limit exceeded for client " + clientId;
        }
    }

    /**
     * Handle the request and update statistics.
     *
     * Bug 4: successCount is incremented in the try block AND again in the finally block.
     */
    private String handleAndCount(Request request) {
        String result = null;
        try {
            result = handle(request);
            successCount.incrementAndGet();
        } finally {
            totalCount.incrementAndGet();
            if (result != null) {
                successCount.incrementAndGet();
            }
        }
        return result;
    }

    protected String handle(Request request) {
        return "PROCESSED: " + request.getId() + " priority=" + request.getPriority();
    }

    public int getTotalCount() {
        return totalCount.get();
    }

    public int getSuccessCount() {
        return successCount.get();
    }

    public int getRejectedCount() {
        return rejectedCount.get();
    }

    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public PriorityRequestQueue getQueue() {
        return queue;
    }

    public void resetStats() {
        totalCount.set(0);
        successCount.set(0);
        rejectedCount.set(0);
    }
}
