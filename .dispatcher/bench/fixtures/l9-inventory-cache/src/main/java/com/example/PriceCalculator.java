package com.example;

/**
 * Price calculator that applies stock-based discounts.
 *
 * BUG 3: {@link #getDiscountedPrice} reads stock level from the cache
 * instead of directly from the store. When the cache holds stale data
 * (e.g., after a stock update that hasn't been invalidated), the
 * discount is computed against the wrong stock level.
 */
public class PriceCalculator {
    private final InventoryStore store;
    private final InventoryCache cache;

    public PriceCalculator(InventoryStore store, InventoryCache cache) {
        this.store = store;
        this.cache = cache;
    }

    /**
     * Calculate discounted price based on stock level.
     * Stock > 100 gets 10% discount.
     *
     * BUG: reads from cache which may have stale stockLevel.
     * Should read directly from InventoryStore instead.
     */
    public double getDiscountedPrice(String productId) {
        Product p = cache.get(productId)
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
        if (p.getStockLevel() > 100) return p.getPrice() * 0.9;
        return p.getPrice();
    }

    /**
     * Calculate discounted price for a quantity.
     * Stock > 100 gets 10% discount on total.
     */
    public double getDiscountedPriceForQuantity(String productId, int quantity) {
        double unitPrice = getDiscountedPrice(productId);
        return unitPrice * quantity;
    }
}
