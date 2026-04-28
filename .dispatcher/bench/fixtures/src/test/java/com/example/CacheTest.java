package com.example;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CacheTest {

    @Test
    void returnsValueBeforeExpiry() {
        Cache<String, String> cache = new Cache<>();
        cache.put("key", "value", 5000); // 5 second TTL
        assertThat(cache.get("key")).isEqualTo("value");
    }

    @Test
    void returnsNullForMissingKey() {
        Cache<String, String> cache = new Cache<>();
        assertThat(cache.get("missing")).isNull();
    }

    @Test
    void expiresAfterTtl() throws InterruptedException {
        Cache<String, String> cache = new Cache<>();
        cache.put("key", "value", 50); // 50ms TTL
        Thread.sleep(100);
        assertThat(cache.get("key")).isNull();
    }

    @Test
    void sizeAfterExpiry() throws InterruptedException {
        Cache<String, Integer> cache = new Cache<>();
        cache.put("a", 1, 50);
        cache.put("b", 2, 5000);
        Thread.sleep(100);
        cache.get("a"); // trigger cleanup
        assertThat(cache.size()).isEqualTo(1);
    }
}
