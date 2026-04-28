package com.example;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * TTL-based read cache backed by InventoryStore.
 *
 * When a cache entry is missing or expired, it falls back to
 * InventoryStore and caches the result.
 *
 * BUG 2: {@link #isExpired} uses strict {@code >} comparison instead
 * of {@code >=}, so entries that have exactly reached their TTL are
 * not evicted and stale values are returned.
 */
public class InventoryCache {
    private final InventoryStore store;
    private final long ttlMs;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public InventoryCache(InventoryStore store, long ttlMs) {
        this(store, ttlMs, System::currentTimeMillis);
    }

    // Package-private constructor for testability
    InventoryCache(InventoryStore store, long ttlMs, LongSupplier clock) {
        this.store = store;
        this.ttlMs = ttlMs;
        this.clock = clock;
    }

    /**
     * Get a product from cache, falling back to store on miss/expiry.
     */
    public Optional<Product> get(String productId) {
        CacheEntry entry = cache.get(productId);
        long now = clock.getAsLong();

        if (entry != null && !isExpired(entry, now)) {
            return Optional.of(entry.product);
        }

        // Cache miss or expired — load from store
        Optional<Product> fromStore = store.get(productId);
        fromStore.ifPresent(p -> cache.put(productId, new CacheEntry(p, now)));
        return fromStore;
    }

    /**
     * Invalidate a cache entry.
     */
    public void invalidate(String productId) {
        cache.remove(productId);
    }

    /**
     * Invalidate all cache entries.
     */
    public void invalidateAll() {
        cache.clear();
    }

    /**
     * Check if a cache entry has expired.
     *
     * BUG: uses {@code >} instead of {@code >=}, so entries at exactly
     * TTL age are not considered expired.
     */
    boolean isExpired(CacheEntry entry, long now) {
        return (now - entry.cachedAt) > ttlMs;
    }

    public int size() {
        // Clean expired entries before reporting size
        long now = clock.getAsLong();
        cache.entrySet().removeIf(e -> isExpired(e.getValue(), now));
        return cache.size();
    }

    record CacheEntry(Product product, long cachedAt) {}
}
