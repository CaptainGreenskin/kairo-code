package com.example;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Primary in-memory data store for products.
 *
 * Protocol contract: callers are responsible for coordinating with
 * InventoryCache — this store does NOT hold a cache reference.
 * After calling {@link #update(String, Function)}, the caller MUST
 * invalidate the corresponding cache entry.
 *
 * BUG 1: {@link #updateProductStock} updates the store but provides
 * no mechanism to notify the cache. The protocol requires callers
 * to manually invalidate the cache after update, but the current
 * implementation does not expose this requirement.
 */
public class InventoryStore {
    private final Map<String, Product> store = new HashMap<>();

    public void addProduct(Product product) {
        store.put(product.getId(), product);
    }

    public Optional<Product> get(String productId) {
        return Optional.ofNullable(store.get(productId));
    }

    /**
     * Update a product using the given transform function.
     * Caller is responsible for cache invalidation after this call.
     */
    public void update(String productId, Function<Product, Product> transform) {
        Product existing = store.get(productId);
        if (existing != null) {
            store.put(productId, transform.apply(existing));
        }
    }

    public void remove(String productId) {
        store.remove(productId);
    }

    public boolean contains(String productId) {
        return store.containsKey(productId);
    }

    public int size() {
        return store.size();
    }

    /**
     * Update stock level for a product.
     *
     * BUG: This method only updates the store. Per the cross-class
     * protocol, the caller must invalidate InventoryCache after this
     * call, but the method doesn't signal this requirement or provide
     * any notification mechanism.
     */
    public void updateProductStock(String productId, int newStock) {
        update(productId, p -> p.withStock(newStock));
        // Missing: no cache invalidation — caller must do this
    }
}
