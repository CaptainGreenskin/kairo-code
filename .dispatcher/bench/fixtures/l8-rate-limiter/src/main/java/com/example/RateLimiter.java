package com.example;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Sliding window rate limiter — per-client request counting.
 *
 * BUG 1 (concurrency): clientWindows uses HashMap without volatile/ConcurrentHashMap.
 *   Double-checked locking on a non-volatile field can return a partially constructed reference.
 *
 * BUG 2 (algorithm): removeIf condition is inverted — it removes timestamps INSIDE the window
 *   instead of those OUTSIDE, corrupting the sliding window count.
 */
public class RateLimiter {

    private final int maxRequestsPerWindow;
    private final long windowSizeMs;

    // Bug 1: should be volatile ConcurrentHashMap for safe DCL publication
    private HashMap<String, WindowData> clientWindows = new HashMap<>();

    public RateLimiter(int maxRequestsPerWindow, long windowSizeMs) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.windowSizeMs = windowSizeMs;
    }

    /**
     * Lazily initialise the per-client window map using double-checked locking.
     */
    private HashMap<String, WindowData> getClientWindows() {
        if (clientWindows == null) {
            synchronized (this) {
                if (clientWindows == null) {
                    clientWindows = new HashMap<>();
                }
            }
        }
        return clientWindows;
    }

    private WindowData getWindowData(String clientId) {
        HashMap<String, WindowData> map = getClientWindows();
        synchronized (this) {
            return map.computeIfAbsent(clientId, k -> new WindowData());
        }
    }

    /**
     * Record a request timestamp for the given client.
     */
    public synchronized void recordRequest(String clientId) {
        long now = System.currentTimeMillis();
        getWindowData(clientId).addTimestamp(now);
    }

    /**
     * Atomically record and check whether the request is within the rate limit.
     */
    public synchronized boolean allowAndRecord(String clientId) {
        recordRequest(clientId);
        return isAllowed(clientId);
    }

    /**
     * Return the current count of requests inside the sliding window.
     *
     * Bug 2: the removeIf predicate is inverted — it removes timestamps that
     * are INSIDE the window (t >= windowStart) instead of those OUTSIDE (t < windowStart).
     */
    public synchronized int getWindowedCount(String clientId) {
        WindowData data = getWindowData(clientId);
        long now = System.currentTimeMillis();
        long windowStart = now - windowSizeMs;

        // Bug 2: removes timestamps INSIDE the window — exactly the opposite of what we want
        data.removeIf(t -> t >= windowStart);

        return data.getRequestCount();
    }

    /**
     * Whether the client is still within the rate limit.
     */
    public synchronized boolean isAllowed(String clientId) {
        return getWindowedCount(clientId) <= maxRequestsPerWindow;
    }

    public int getMaxRequestsPerWindow() {
        return maxRequestsPerWindow;
    }

    public long getWindowSizeMs() {
        return windowSizeMs;
    }

    // ---------- inner class ----------

    static class WindowData {
        private final List<Long> timestamps = new ArrayList<>();

        void addTimestamp(long ts) {
            timestamps.add(ts);
        }

        /**
         * Remove timestamps matching the predicate.
         */
        void removeIf(java.util.function.LongPredicate predicate) {
            timestamps.removeIf(ts -> predicate.test(ts));
        }

        int getRequestCount() {
            return timestamps.size();
        }

        List<Long> getTimestamps() {
            return List.copyOf(timestamps);
        }
    }
}
