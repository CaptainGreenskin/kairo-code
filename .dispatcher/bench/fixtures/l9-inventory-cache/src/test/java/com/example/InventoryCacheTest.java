package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InventoryCacheTest {
    private InventoryStore store;
    private InventoryCache cache;

    @BeforeEach
    void setUp() {
        store = new InventoryStore();
        cache = new InventoryCache(store, 1000L); // 1 second TTL
    }

    @Test
    void shouldCacheProductFromStore() {
        store.addProduct(new Product("p1", "Widget", "Electronics", 29.99, 50));

        Optional<Product> result = cache.get("p1");
        assertTrue(result.isPresent());
        assertEquals("Widget", result.get().getName());
    }

    @Test
    void shouldReturnEmptyForMissingProduct() {
        Optional<Product> result = cache.get("nonexistent");
        assertFalse(result.isPresent());
    }

    @Test
    void shouldInvalidateEntry() {
        store.addProduct(new Product("p1", "Widget", "Electronics", 29.99, 50));
        cache.get("p1"); // cache it

        cache.invalidate("p1");

        // After invalidation, must reload from store
        Optional<Product> result = cache.get("p1");
        assertTrue(result.isPresent());
        assertEquals("Widget", result.get().getName());
    }

    @Test
    void shouldInvalidateAllEntries() {
        store.addProduct(new Product("p1", "A", "Cat", 10.0, 5));
        store.addProduct(new Product("p2", "B", "Cat", 20.0, 10));
        cache.get("p1");
        cache.get("p2");

        cache.invalidateAll();

        assertEquals(0, cache.size());
    }

    @Test
    void shouldReportCacheSize() {
        store.addProduct(new Product("p1", "A", "Cat", 10.0, 5));
        store.addProduct(new Product("p2", "B", "Cat", 20.0, 10));
        cache.get("p1");
        cache.get("p2");

        assertEquals(2, cache.size());
    }

    @Test
    void shouldReloadFromStoreAfterInvalidate() {
        store.addProduct(new Product("p1", "Widget", "Electronics", 29.99, 50));
        cache.get("p1");

        // Update store directly
        store.updateProductStock("p1", 200);
        cache.invalidate("p1");

        Optional<Product> result = cache.get("p1");
        assertTrue(result.isPresent());
        assertEquals(200, result.get().getStockLevel());
    }

    @Test
    void shouldExpireEntryAfterTTL() throws InterruptedException {
        store.addProduct(new Product("p1", "Widget", "Electronics", 29.99, 50));
        cache.get("p1");

        // Update store
        store.updateProductStock("p1", 300);

        // Wait for TTL to expire
        Thread.sleep(1100);

        Optional<Product> result = cache.get("p1");
        assertTrue(result.isPresent());
        assertEquals(300, result.get().getStockLevel());
    }

    @Test
    void shouldNotExpireEntryBeforeTTL() throws InterruptedException {
        store.addProduct(new Product("p1", "Widget", "Electronics", 29.99, 50));
        cache.get("p1");

        // Update store
        store.updateProductStock("p1", 300);

        // Wait less than TTL
        Thread.sleep(500);

        // Should still return cached (old) value
        Optional<Product> result = cache.get("p1");
        assertTrue(result.isPresent());
        assertEquals(50, result.get().getStockLevel());
    }

    @Test
    void shouldExpireEntryAtExactTTLBoundary() {
        // Bug 2 test: TTL check uses > instead of >=,
        // so entries at exactly TTL should be expired
        // Use injectable clock for deterministic testing
        long[] time = new long[]{1000L};
        InventoryCache controlledCache = new InventoryCache(store, 100L, () -> time[0]);

        store.addProduct(new Product("p1", "Widget", "Electronics", 29.99, 50));
        controlledCache.get("p1"); // cachedAt = 1000

        // Update store
        store.updateProductStock("p1", 400);

        // Advance time to exactly TTL boundary (1000 + 100 = 1100)
        time[0] = 1100L;

        // At exact TTL boundary (age == ttlMs), entry should be expired
        // Bug: (1100 - 1000) > 100 → 100 > 100 → false (NOT expired) — returns stale 50
        // Fix: (1100 - 1000) >= 100 → 100 >= 100 → true (expired) — returns fresh 400
        Optional<Product> result = controlledCache.get("p1");
        assertTrue(result.isPresent());
        assertEquals(400, result.get().getStockLevel());
    }

    @Test
    void shouldHandleConcurrentAccess() throws InterruptedException {
        store.addProduct(new Product("p1", "Widget", "Electronics", 29.99, 50));

        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    cache.get("p1");
                }
            });
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        // Should not throw and should return valid data
        Optional<Product> result = cache.get("p1");
        assertTrue(result.isPresent());
    }
}
