package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InventoryStoreTest {
    private InventoryStore store;

    @BeforeEach
    void setUp() {
        store = new InventoryStore();
    }

    @Test
    void shouldAddAndGetProduct() {
        Product p = new Product("p1", "Widget", "Electronics", 29.99, 50);
        store.addProduct(p);

        Optional<Product> result = store.get("p1");
        assertTrue(result.isPresent());
        assertEquals("Widget", result.get().getName());
        assertEquals(50, result.get().getStockLevel());
    }

    @Test
    void shouldReturnEmptyForMissingProduct() {
        Optional<Product> result = store.get("nonexistent");
        assertFalse(result.isPresent());
    }

    @Test
    void shouldUpdateProduct() {
        Product p = new Product("p1", "Widget", "Electronics", 29.99, 50);
        store.addProduct(p);

        store.update("p1", prod -> prod.withPrice(24.99));

        Optional<Product> result = store.get("p1");
        assertTrue(result.isPresent());
        assertEquals(24.99, result.get().getPrice());
    }

    @Test
    void shouldUpdateStockViaUpdateProductStock() {
        Product p = new Product("p1", "Widget", "Electronics", 29.99, 50);
        store.addProduct(p);

        store.updateProductStock("p1", 200);

        Optional<Product> result = store.get("p1");
        assertTrue(result.isPresent());
        assertEquals(200, result.get().getStockLevel());
    }

    @Test
    void shouldRemoveProduct() {
        Product p = new Product("p1", "Widget", "Electronics", 29.99, 50);
        store.addProduct(p);

        store.remove("p1");

        assertFalse(store.get("p1").isPresent());
    }

    @Test
    void shouldCheckContains() {
        Product p = new Product("p1", "Widget", "Electronics", 29.99, 50);
        store.addProduct(p);

        assertTrue(store.contains("p1"));
        assertFalse(store.contains("p2"));
    }

    @Test
    void shouldTrackSize() {
        assertEquals(0, store.size());

        store.addProduct(new Product("p1", "A", "Cat", 10.0, 5));
        assertEquals(1, store.size());

        store.addProduct(new Product("p2", "B", "Cat", 20.0, 10));
        assertEquals(2, store.size());
    }

    @Test
    void sizeShouldDecreaseOnRemove() {
        store.addProduct(new Product("p1", "A", "Cat", 10.0, 5));
        store.addProduct(new Product("p2", "B", "Cat", 20.0, 10));
        assertEquals(2, store.size());

        store.remove("p1");
        assertEquals(1, store.size());
    }

    @Test
    void updateShouldNotAffectMissingProduct() {
        store.update("nonexistent", p -> p.withStock(999));
        assertFalse(store.get("nonexistent").isPresent());
    }

    @Test
    void updateProductStockShouldNotAffectMissingProduct() {
        store.updateProductStock("nonexistent", 999);
        assertFalse(store.get("nonexistent").isPresent());
    }

    @Test
    void shouldHandleMultipleUpdates() {
        Product p = new Product("p1", "Widget", "Electronics", 29.99, 50);
        store.addProduct(p);

        store.updateProductStock("p1", 100);
        store.updateProductStock("p1", 75);

        assertEquals(75, store.get("p1").get().getStockLevel());
    }

    @Test
    void shouldStoreDifferentCategories() {
        store.addProduct(new Product("p1", "A", "Electronics", 10.0, 5));
        store.addProduct(new Product("p2", "B", "Books", 15.0, 20));

        assertEquals("Electronics", store.get("p1").get().getCategory());
        assertEquals("Books", store.get("p2").get().getCategory());
    }
}
