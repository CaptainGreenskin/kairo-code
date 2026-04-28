package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InventoryServiceTest {
    private InventoryStore store;
    private InventoryCache cache;
    private EventBus eventBus;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        store = new InventoryStore();
        cache = new InventoryCache(store, 1000L);
        eventBus = new EventBus();
        service = new InventoryService(store, cache, eventBus);
    }

    @Test
    void shouldGetProductFromCache() {
        store.addProduct(new Product("p1", "Widget", "Electronics", 29.99, 50));

        Optional<Product> result = service.getProduct("p1");
        assertTrue(result.isPresent());
        assertEquals("Widget", result.get().getName());
    }

    @Test
    void shouldReturnEmptyForMissingProduct() {
        Optional<Product> result = service.getProduct("nonexistent");
        assertFalse(result.isPresent());
    }

    @Test
    void shouldAddProductAndInvalidateCache() {
        Product p = new Product("p1", "Widget", "Electronics", 29.99, 50);
        service.addProduct(p);

        Optional<Product> result = service.getProduct("p1");
        assertTrue(result.isPresent());
        assertEquals("Widget", result.get().getName());
    }

    @Test
    void shouldUpdateStock() {
        store.addProduct(new Product("p1", "Widget", "Electronics", 29.99, 50));
        cache.get("p1");

        service.updateStock("p1", 200);

        // After fix, cache should be invalidated so store value is returned
        Optional<Product> result = service.getProduct("p1");
        assertTrue(result.isPresent());
        assertEquals(200, result.get().getStockLevel());
    }

    @Test
    void shouldUpdateStockWithZero() {
        store.addProduct(new Product("p1", "Widget", "Electronics", 29.99, 50));
        service.updateStock("p1", 0);

        assertEquals(0, store.get("p1").get().getStockLevel());
    }

    @Test
    void shouldRestockProduct() {
        store.addProduct(new Product("p1", "Widget", "Electronics", 29.99, 50));

        service.restockProduct("p1", 30);

        assertEquals(80, store.get("p1").get().getStockLevel());
    }

    @Test
    void restockShouldPublishEvent() {
        store.addProduct(new Product("p1", "Widget", "Electronics", 29.99, 50));

        service.restockProduct("p1", 30);

        List<EventBus.Event> events = service.getPublishedEvents();
        assertEquals(1, events.size());
        assertEquals("restock", events.get(0).eventType());
        assertEquals("p1", events.get(0).productId());
    }

    @Test
    void restockEventShouldSeeUpdatedStock() {
        // Bug 4 test: event should be published AFTER stock update
        // so subscribers see the new stock level
        store.addProduct(new Product("p1", "Widget", "Electronics", 29.99, 50));

        // Simulate a subscriber that reads stock when event fires
        int[] stockAtEventTime = new int[1];
        eventBus.addSubscriber((eventType, productId) -> {
            stockAtEventTime[0] = store.get(productId).map(Product::getStockLevel).orElse(-1);
        });

        service.restockProduct("p1", 100);

        // After fix, subscriber should see updated stock (150), not old (50)
        assertEquals(150, stockAtEventTime[0]);
    }

    @Test
    void restockEventOrderShouldBeUpdateThenPublish() {
        // Test that events are published in correct order
        store.addProduct(new Product("p1", "A", "Cat", 10.0, 10));
        store.addProduct(new Product("p2", "B", "Cat", 20.0, 20));

        service.restockProduct("p1", 5);
        service.restockProduct("p2", 10);

        List<EventBus.Event> events = service.getPublishedEvents();
        assertEquals(2, events.size());
        assertEquals("p1", events.get(0).productId());
        assertEquals("p2", events.get(1).productId());
    }

    @Test
    void shouldRemoveProduct() {
        store.addProduct(new Product("p1", "Widget", "Electronics", 29.99, 50));

        service.removeProduct("p1");

        assertFalse(store.get("p1").isPresent());
    }

    @Test
    void removeProductShouldNotBeVisibleInCache() {
        // Bug 5 test: after removal, cache should not return the product
        store.addProduct(new Product("p1", "Widget", "Electronics", 29.99, 50));
        cache.get("p1"); // warm cache

        service.removeProduct("p1");

        Optional<Product> result = service.getProduct("p1");
        assertFalse(result.isPresent());
    }

    @Test
    void shouldClearEvents() {
        store.addProduct(new Product("p1", "Widget", "Electronics", 29.99, 50));
        service.restockProduct("p1", 30);
        assertEquals(1, service.getPublishedEvents().size());

        service.clearEvents();
        assertEquals(0, service.getPublishedEvents().size());
    }

    @Test
    void shouldHandleMultipleStockUpdates() {
        store.addProduct(new Product("p1", "Widget", "Electronics", 29.99, 50));
        cache.get("p1");

        service.updateStock("p1", 100);
        service.updateStock("p1", 75);

        Optional<Product> result = service.getProduct("p1");
        assertTrue(result.isPresent());
        assertEquals(75, result.get().getStockLevel());
    }

    @Test
    void shouldHandleAddThenRemove() {
        Product p = new Product("p1", "Widget", "Electronics", 29.99, 50);
        service.addProduct(p);
        assertTrue(service.getProduct("p1").isPresent());

        service.removeProduct("p1");
        assertFalse(service.getProduct("p1").isPresent());
    }

    @Test
    void shouldHandleRestockThenRemove() {
        store.addProduct(new Product("p1", "Widget", "Electronics", 29.99, 50));

        service.restockProduct("p1", 25);
        assertEquals(75, store.get("p1").get().getStockLevel());

        service.removeProduct("p1");
        assertFalse(store.get("p1").isPresent());
        assertFalse(service.getProduct("p1").isPresent());
    }
}
