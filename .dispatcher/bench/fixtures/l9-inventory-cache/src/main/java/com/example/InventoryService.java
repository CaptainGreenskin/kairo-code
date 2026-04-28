package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrating service for inventory operations.
 *
 * BUG 4: {@link #restockProduct} publishes the restock event BEFORE
 * updating the store. Subscribers who read the store upon receiving
 * the event will see stale stock levels.
 *
 * BUG 5: {@link #removeProduct} removes the product from the store
 * but does NOT invalidate the cache. Subsequent cache.get() calls
 * within TTL will return the deleted product.
 */
public class InventoryService {
    private final InventoryStore store;
    private final InventoryCache cache;
    private final EventBus eventBus;

    public InventoryService(InventoryStore store, InventoryCache cache, EventBus eventBus) {
        this.store = store;
        this.cache = cache;
        this.eventBus = eventBus;
    }

    public Optional<Product> getProduct(String productId) {
        return cache.get(productId);
    }

    public void addProduct(Product product) {
        store.addProduct(product);
        cache.invalidate(product.getId());
    }

    /**
     * Update stock level for a product.
     *
     * BUG 1 (protocol violation): only updates the store but does NOT
     * invalidate the cache. After this call, cache.get(productId)
     * returns the old stock level until TTL expires.
     */
    public void updateStock(String productId, int newStock) {
        store.updateProductStock(productId, newStock);
        // Missing: cache.invalidate(productId)
    }

    /**
     * Restock a product by adding quantity to current stock.
     *
     * BUG 4: publishes event BEFORE updating store. Subscribers
     * reading the store will see old stock levels.
     */
    public void restockProduct(String productId, int quantity) {
        eventBus.publish("restock", productId);
        store.update(productId, p -> p.withStock(p.getStockLevel() + quantity));
        // Missing: cache.invalidate(productId)
    }

    /**
     * Remove a product from the inventory.
     *
     * BUG 5: removes from store but does NOT invalidate cache.
     * cache.get(productId) may still return the deleted product.
     */
    public void removeProduct(String productId) {
        store.remove(productId);
        // Missing: cache.invalidate(productId)
    }

    /**
     * Get list of published events (for testing).
     */
    public List<EventBus.Event> getPublishedEvents() {
        return eventBus.getPublishedEvents();
    }

    /**
     * Clear published events (for testing).
     */
    public void clearEvents() {
        eventBus.clear();
    }
}
