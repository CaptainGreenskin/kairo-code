package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryTrackerTest {

    private InventoryTracker tracker;
    private Product product;

    @BeforeEach
    void setUp() {
        product = new Product("PROD-001", "Widget", 10.0, 100);
        tracker = new InventoryTracker(Map.of("PROD-001", product));
    }

    @Test
    void testReserveStock() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-001", 5, 10.0));
        boolean result = tracker.reserve(order);
        assertThat(result).isTrue();
        assertThat(tracker.getReservedQuantity("ORD-001", "PROD-001")).isEqualTo(5);
        assertThat(product.getAvailableStock()).isEqualTo(95);
    }

    @Test
    void testReserveInsufficientStock() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-001", 200, 10.0));
        boolean result = tracker.reserve(order);
        assertThat(result).isFalse();
        assertThat(product.getAvailableStock()).isEqualTo(100);
    }

    @Test
    void testReleaseStock() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-001", 10, 10.0));
        tracker.reserve(order);
        tracker.release(order);
        assertThat(product.getAvailableStock()).isEqualTo(100);
    }

    @Test
    void testReleaseDoesNotExceedMaxStock() {
        // Reserve 10 via tracker
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-001", 10, 10.0));
        tracker.reserve(order);
        // Release through tracker (correctly tracked)
        tracker.release(order);
        // Now release more directly on the product (simulates a bug where release is called twice)
        product.releaseStock(10);
        // Available stock should never exceed maxStock
        assertThat(product.getAvailableStock()).isLessThanOrEqualTo(product.getMaxStock());
    }

    @Test
    void testMultipleReservations() {
        Order order1 = new Order("ORD-001", "CUST-001");
        order1.addItem(new OrderItem("PROD-001", 30, 10.0));
        Order order2 = new Order("ORD-002", "CUST-002");
        order2.addItem(new OrderItem("PROD-001", 20, 10.0));
        tracker.reserve(order1);
        tracker.reserve(order2);
        assertThat(product.getAvailableStock()).isEqualTo(50);
        assertThat(tracker.getTotalReserved("PROD-001")).isEqualTo(50);
    }

    @Test
    void testReleaseMultipleOrders() {
        Order order1 = new Order("ORD-001", "CUST-001");
        order1.addItem(new OrderItem("PROD-001", 30, 10.0));
        Order order2 = new Order("ORD-002", "CUST-002");
        order2.addItem(new OrderItem("PROD-001", 20, 10.0));
        tracker.reserve(order1);
        tracker.reserve(order2);
        tracker.release(order1);
        assertThat(product.getAvailableStock()).isEqualTo(80);
        tracker.release(order2);
        assertThat(product.getAvailableStock()).isEqualTo(100);
    }

    @Test
    void testReserveUnknownProduct() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("UNKNOWN", 5, 10.0));
        boolean result = tracker.reserve(order);
        assertThat(result).isFalse();
    }
}
