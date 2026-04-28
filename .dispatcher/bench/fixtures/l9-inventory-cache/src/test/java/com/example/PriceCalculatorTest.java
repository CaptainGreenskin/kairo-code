package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PriceCalculatorTest {
    private InventoryStore store;
    private InventoryCache cache;
    private PriceCalculator calculator;

    @BeforeEach
    void setUp() {
        store = new InventoryStore();
        cache = new InventoryCache(store, 1000L);
        calculator = new PriceCalculator(store, cache);
    }

    @Test
    void shouldReturnFullPriceWhenStockLow() {
        store.addProduct(new Product("p1", "Widget", "Electronics", 100.0, 50));
        cache.get("p1");

        double price = calculator.getDiscountedPrice("p1");
        assertEquals(100.0, price);
    }

    @Test
    void shouldApplyDiscountWhenStockHigh() {
        store.addProduct(new Product("p1", "Widget", "Electronics", 100.0, 150));
        cache.get("p1");

        double price = calculator.getDiscountedPrice("p1");
        assertEquals(90.0, price);
    }

    @Test
    void shouldApplyDiscountAtStockThreshold() {
        store.addProduct(new Product("p1", "Widget", "Electronics", 100.0, 101));
        cache.get("p1");

        double price = calculator.getDiscountedPrice("p1");
        assertEquals(90.0, price);
    }

    @Test
    void shouldNotApplyDiscountAtExactBoundary() {
        store.addProduct(new Product("p1", "Widget", "Electronics", 100.0, 100));
        cache.get("p1");

        double price = calculator.getDiscountedPrice("p1");
        assertEquals(100.0, price);
    }

    @Test
    void shouldCalculateQuantityPrice() {
        store.addProduct(new Product("p1", "Widget", "Electronics", 100.0, 150));
        cache.get("p1");

        double total = calculator.getDiscountedPriceForQuantity("p1", 5);
        assertEquals(450.0, total);
    }

    @Test
    void shouldThrowForMissingProduct() {
        assertThrows(IllegalArgumentException.class, () -> calculator.getDiscountedPrice("nonexistent"));
    }

    @Test
    void shouldUseFreshStockLevelNotCached() {
        // This tests Bug 3: calculator should use store data, not cache
        store.addProduct(new Product("p1", "Widget", "Electronics", 100.0, 50));
        cache.get("p1"); // cache has stockLevel=50

        // Update stock in store to 200 (above discount threshold)
        // Do NOT invalidate cache — calculator should read from store directly
        store.updateProductStock("p1", 200);

        double price = calculator.getDiscountedPrice("p1");
        assertEquals(90.0, price);
    }

    @Test
    void shouldReflectStockChangeInPrice() {
        store.addProduct(new Product("p1", "Widget", "Electronics", 100.0, 200));
        cache.get("p1");

        // Initially discounted
        assertEquals(90.0, calculator.getDiscountedPrice("p1"));

        // Update stock below threshold — do NOT invalidate cache
        store.updateProductStock("p1", 30);

        // Should now be full price since calculator reads from store
        assertEquals(100.0, calculator.getDiscountedPrice("p1"));
    }
}
