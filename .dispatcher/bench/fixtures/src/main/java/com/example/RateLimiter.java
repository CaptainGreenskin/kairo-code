package com.example;

public class RateLimiter {
    private final double maxTokens;
    private final double refillRatePerSecond;
    private double tokens;
    private long lastRefillTimestamp;

    public RateLimiter(double maxTokens, double refillRatePerSecond) {
        this.maxTokens = maxTokens;
        this.refillRatePerSecond = refillRatePerSecond;
        this.tokens = maxTokens;
        this.lastRefillTimestamp = System.currentTimeMillis();
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefillTimestamp;
        // BUG: divides by 1000 twice, making refill 1000x slower than intended
        double secondsElapsed = (elapsed / 1000.0) / 1000.0;
        tokens = Math.min(maxTokens, tokens + secondsElapsed * refillRatePerSecond);
        lastRefillTimestamp = now;
    }

    public synchronized boolean tryAcquire(int count) {
        refill();
        if (tokens >= count) {
            tokens -= count;
            return true;
        }
        return false;
    }

    public synchronized double getTokens() {
        refill();
        return tokens;
    }
}
