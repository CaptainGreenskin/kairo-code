package com.example;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTest {

    @Test
    void testInitialState() {
        Order order = new Order("ORD-001", "CUST-001");
        assertThat(order.getStatus()).isEqualTo(Order.Status.DRAFT);
        assertThat(order.getItems()).isEmpty();
        assertThat(order.getTotalItems()).isZero();
    }

    @Test
    void testAddItem() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-001", 2, 19.99));
        assertThat(order.getItems()).hasSize(1);
        assertThat(order.getTotalItems()).isEqualTo(2);
    }

    @Test
    void testAddItemWithSameProductMerges() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-001", 2, 19.99));
        order.addItem(new OrderItem("PROD-001", 3, 19.99));
        // Should merge into one item with quantity 5, not two separate items
        assertThat(order.getItems()).hasSize(1);
        assertThat(order.getTotalItems()).isEqualTo(5);
        assertThat(order.getItems().get(0).getQuantity()).isEqualTo(5);
    }

    @Test
    void testAddMultipleDifferentProducts() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-001", 2, 19.99));
        order.addItem(new OrderItem("PROD-002", 1, 29.99));
        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getTotalItems()).isEqualTo(3);
    }

    @Test
    void testRemoveItem() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-001", 2, 19.99));
        order.addItem(new OrderItem("PROD-002", 1, 29.99));
        order.removeItem("PROD-001");
        assertThat(order.getItems()).hasSize(1);
        assertThat(order.findItem("PROD-001")).isEmpty();
        assertThat(order.findItem("PROD-002")).isPresent();
    }

    @Test
    void testFindItem() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-001", 2, 19.99));
        assertThat(order.findItem("PROD-001")).isPresent();
        assertThat(order.findItem("PROD-002")).isEmpty();
    }

    @Test
    void testGetTotalItems() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-001", 3, 10.0));
        order.addItem(new OrderItem("PROD-002", 2, 20.0));
        assertThat(order.getTotalItems()).isEqualTo(5);
    }

    @Test
    void testStatusTransitionToPlaced() {
        Order order = new Order("ORD-001", "CUST-001");
        order.setStatus(Order.Status.PLACED);
        assertThat(order.getStatus()).isEqualTo(Order.Status.PLACED);
    }

    @Test
    void testStatusTransitionToCancelled() {
        Order order = new Order("ORD-001", "CUST-001");
        order.setStatus(Order.Status.PLACED);
        order.setStatus(Order.Status.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(Order.Status.CANCELLED);
    }

    @Test
    void testItemsReturnsCopy() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-001", 1, 10.0));
        order.getItems().clear();
        assertThat(order.getItems()).hasSize(1);
    }
}
