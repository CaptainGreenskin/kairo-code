package com.example;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks inventory reservations for orders.
 */
public class InventoryTracker {
    private final Map<String, Product> products;
    private final Map<String, Map<String, Integer>> reservations; // orderId -> (productId -> quantity)

    public InventoryTracker(Map<String, Product> products) {
        this.products = new HashMap<>(products);
        this.reservations = new HashMap<>();
    }

    public Product getProduct(String productId) {
        return products.get(productId);
    }

    /**
     * Reserve inventory for all items in an order.
     * @return true if all items could be reserved, false otherwise
     */
    public boolean reserve(Order order) {
        Map<String, Integer> orderReservations = new HashMap<>();

        for (OrderItem item : order.getItems()) {
            Product product = products.get(item.getProductId());
            if (product == null) {
                rollback(order.getId(), orderReservations);
                return false;
            }
            if (!product.reserveStock(item.getQuantity())) {
                rollback(order.getId(), orderReservations);
                return false;
            }
            orderReservations.put(item.getProductId(), item.getQuantity());
        }

        reservations.put(order.getId(), orderReservations);
        return true;
    }

    /**
     * Release previously reserved inventory for an order.
     */
    public void release(Order order) {
        Map<String, Integer> orderReservations = reservations.remove(order.getId());
        if (orderReservations == null) {
            return;
        }

        for (Map.Entry<String, Integer> entry : orderReservations.entrySet()) {
            Product product = products.get(entry.getKey());
            if (product != null) {
                // Bug #4: releaseStock doesn't cap at maxStock — stock can exceed original capacity
                product.releaseStock(entry.getValue());
            }
        }
    }

    /**
     * Get the reserved quantity for a product in a specific order.
     */
    public int getReservedQuantity(String orderId, String productId) {
        Map<String, Integer> orderReservations = reservations.get(orderId);
        if (orderReservations == null) {
            return 0;
        }
        return orderReservations.getOrDefault(productId, 0);
    }

    /**
     * Get the total reserved stock across all orders for a product.
     */
    public int getTotalReserved(String productId) {
        int total = 0;
        for (Map<String, Integer> orderReservations : reservations.values()) {
            total += orderReservations.getOrDefault(productId, 0);
        }
        return total;
    }

    private void rollback(String orderId, Map<String, Integer> reservations) {
        for (Map.Entry<String, Integer> entry : reservations.entrySet()) {
            Product product = products.get(entry.getKey());
            if (product != null) {
                product.releaseStock(entry.getValue());
            }
        }
    }
}
