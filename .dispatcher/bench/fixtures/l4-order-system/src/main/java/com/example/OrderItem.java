package com.example;

/**
 * Represents a line item in an order.
 */
public class OrderItem {
    private final String productId;
    private final int quantity;
    private final double unitPrice;

    public OrderItem(String productId, int quantity, double unitPrice) {
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public String getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public double getUnitPrice() {
        return unitPrice;
    }

    public double getSubtotal() {
        return quantity * unitPrice;
    }

    @Override
    public String toString() {
        return "OrderItem{productId='%s', quantity=%d, unitPrice=%.2f}"
                .formatted(productId, quantity, unitPrice);
    }
}
