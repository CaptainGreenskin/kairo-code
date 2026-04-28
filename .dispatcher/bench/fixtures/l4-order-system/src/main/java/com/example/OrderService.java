package com.example;

/**
 * Orchestrates order processing: pricing, inventory, and order lifecycle.
 */
public class OrderService {
    private final PricingEngine pricingEngine;
    private final InventoryTracker inventoryTracker;

    public OrderService(PricingEngine pricingEngine, InventoryTracker inventoryTracker) {
        this.pricingEngine = pricingEngine;
        this.inventoryTracker = inventoryTracker;
    }

    /**
     * Place an order: reserve inventory, calculate price, and mark as placed.
     * @throws InsufficientStockException if any item cannot be reserved
     */
    public double placeOrder(Order order) {
        if (order.getStatus() != Order.Status.DRAFT) {
            throw new IllegalStateException("Order must be in DRAFT status to place");
        }

        // Reserve stock for each item
        for (OrderItem item : order.getItems()) {
            Product product = inventoryTracker.getProduct(item.getProductId());
            if (product == null || !product.reserveStock(item.getQuantity())) {
                throw new InsufficientStockException("Insufficient stock for order: " + order.getId());
            }
        }

        // Calculate price
        double total = pricingEngine.calculateTotal(order);
        order.setStatus(Order.Status.PLACED);
        return total;
    }

    /**
     * Cancel an order: release inventory and mark as cancelled.
     */
    public void cancelOrder(Order order) {
        if (order.getStatus() != Order.Status.PLACED) {
            throw new IllegalStateException("Order must be in PLACED status to cancel");
        }

        for (OrderItem item : order.getItems()) {
            Product product = inventoryTracker.getProduct(item.getProductId());
            if (product != null) {
                product.releaseStock(item.getQuantity());
            }
        }
        order.setStatus(Order.Status.CANCELLED);
    }

    /**
     * Exception thrown when stock is insufficient for an order.
     */
    public static class InsufficientStockException extends RuntimeException {
        public InsufficientStockException(String message) {
            super(message);
        }
    }
}
