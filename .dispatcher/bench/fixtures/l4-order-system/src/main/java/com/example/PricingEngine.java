package com.example;

/**
 * Pricing engine for order total calculation, discount, and tax.
 */
public class PricingEngine {

    private static final double TAX_RATE = 0.08; // 8%

    /**
     * Calculate the total price of an order.
     */
    public double calculateTotal(Order order) {
        double subtotal = order.getItems().stream()
                .mapToDouble(OrderItem::getSubtotal)
                .sum();
        return subtotal;
    }

    /**
     * Apply a percentage discount to a price.
     */
    public double applyDiscount(double price, int discountPercent) {
        double discount = price * (discountPercent / 100);
        return price - discount;
    }

    /**
     * Calculate tax on a price.
     */
    public double calculateTax(double price) {
        return price * TAX_RATE;
    }

    /**
     * Calculate final price: subtotal with optional discount and tax.
     */
    public double calculateFinalPrice(Order order, int discountPercent) {
        double subtotal = calculateTotal(order);
        double discounted = applyDiscount(subtotal, discountPercent);
        return discounted + calculateTax(discounted);
    }
}
