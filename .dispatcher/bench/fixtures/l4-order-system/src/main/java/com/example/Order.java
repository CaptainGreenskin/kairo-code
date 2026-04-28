package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Order domain model.
 */
public class Order {
    public enum Status {
        DRAFT, PLACED, CANCELLED
    }

    private final String id;
    private final String customerId;
    private final List<OrderItem> items;
    private Status status;

    public Order(String id, String customerId) {
        this.id = id;
        this.customerId = customerId;
        this.items = new ArrayList<>();
        this.status = Status.DRAFT;
    }

    public String getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public List<OrderItem> getItems() {
        return new ArrayList<>(items);
    }

    public Status getStatus() {
        return status;
    }

    /**
     * Add an item to the order. If the product already exists in the order,
     * merge the quantities.
     */
    public void addItem(OrderItem item) {
        items.add(item);
    }

    public void removeItem(String productId) {
        items.removeIf(i -> i.getProductId().equals(productId));
    }

    public Optional<OrderItem> findItem(String productId) {
        return items.stream()
                .filter(i -> i.getProductId().equals(productId))
                .findFirst();
    }

    public int getTotalItems() {
        return items.stream()
                .mapToInt(OrderItem::getQuantity)
                .sum();
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "Order{id='%s', customerId='%s', status=%s, items=%d}"
                .formatted(id, customerId, status, items.size());
    }
}
