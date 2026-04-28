package com.example;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void consumesTokensOnAcquire() {
        RateLimiter limiter = new RateLimiter(10.0, 10.0);
        assertThat(limiter.tryAcquire(3)).isTrue();
        assertThat(limiter.getTokens()).isLessThan(8.0);
    }

    @Test
    void rejectsWhenInsufficientTokens() {
        RateLimiter limiter = new RateLimiter(5.0, 1.0);
        assertThat(limiter.tryAcquire(5)).isTrue();
        assertThat(limiter.tryAcquire(1)).isFalse();
    }

    @Test
    void refillsTokensOverTime() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(10.0, 100.0); // 100 tokens/sec
        limiter.tryAcquire(10); // drain all
        Thread.sleep(100); // wait 100ms -> should refill ~10 tokens
        assertThat(limiter.tryAcquire(5)).isTrue();
    }

    @Test
    void neverExceedsMaxTokens() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(10.0, 1000.0);
        Thread.sleep(50);
        assertThat(limiter.getTokens()).isLessThanOrEqualTo(10.0);
    }
}
