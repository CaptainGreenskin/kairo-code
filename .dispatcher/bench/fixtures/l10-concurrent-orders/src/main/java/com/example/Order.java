package com.example;

import java.util.Objects;

public class Order {

    private final String id;
    private final String productId;
    private final int quantity;
    private OrderStatus status;
    private final double amount;

    public Order(String id, String productId, int quantity, double amount) {
        this.id = Objects.requireNonNull(id);
        this.productId = Objects.requireNonNull(productId);
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (amount < 0) throw new IllegalArgumentException("amount must be non-negative");
        this.quantity = quantity;
        this.amount = amount;
        this.status = OrderStatus.PENDING;
    }

    public String id() { return id; }
    public String productId() { return productId; }
    public int quantity() { return quantity; }
    public OrderStatus status() { return status; }
    public double amount() { return amount; }

    public void complete() {
        if (this.status != OrderStatus.PENDING)
            throw new IllegalStateException("Order not PENDING: " + status);
        this.status = OrderStatus.COMPLETED;
    }

    public void cancel() {
        if (this.status != OrderStatus.PENDING)
            throw new IllegalStateException("Order not PENDING: " + status);
        this.status = OrderStatus.CANCELLED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Order order)) return false;
        return id.equals(order.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Order{id='%s', productId='%s', quantity=%d, status=%s, amount=%.2f}"
                .formatted(id, productId, quantity, status, amount);
    }
}
