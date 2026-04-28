package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderServiceTest {

    private PricingEngine pricingEngine;
    private InventoryTracker inventoryTracker;
    private OrderService orderService;
    private Product productA;
    private Product productB;

    @BeforeEach
    void setUp() {
        pricingEngine = new PricingEngine();
        productA = new Product("PROD-A", "Widget A", 20.0, 50);
        productB = new Product("PROD-B", "Widget B", 30.0, 30);
        inventoryTracker = new InventoryTracker(Map.of("PROD-A", productA, "PROD-B", productB));
        orderService = new OrderService(pricingEngine, inventoryTracker);
    }

    @Test
    void testPlaceOrderHappyPath() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-A", 2, 20.0));
        double total = orderService.placeOrder(order);
        assertThat(total).isEqualTo(40.0);
        assertThat(order.getStatus()).isEqualTo(Order.Status.PLACED);
    }

    @Test
    void testPlaceOrderReservesInventory() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-A", 5, 20.0));
        orderService.placeOrder(order);
        assertThat(productA.getAvailableStock()).isEqualTo(45);
    }

    @Test
    void testPlaceOrderInsufficientStock() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-A", 100, 20.0));
        assertThatThrownBy(() -> orderService.placeOrder(order))
                .isInstanceOf(OrderService.InsufficientStockException.class);
    }

    @Test
    void testPlaceOrderRollsBackOnInsufficientStock() {
        // Add items for both products — PROD-A has enough stock, PROD-B does not
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-A", 5, 20.0));
        order.addItem(new OrderItem("PROD-B", 100, 30.0));
        try {
            orderService.placeOrder(order);
        } catch (OrderService.InsufficientStockException ignored) {
        }
        // PROD-A stock should be rolled back to original (50)
        assertThat(productA.getAvailableStock()).isEqualTo(50);
    }

    @Test
    void testCancelOrder() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-A", 5, 20.0));
        orderService.placeOrder(order);
        orderService.cancelOrder(order);
        assertThat(order.getStatus()).isEqualTo(Order.Status.CANCELLED);
        assertThat(productA.getAvailableStock()).isEqualTo(50);
    }

    @Test
    void testCannotCancelDraftOrder() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-A", 5, 20.0));
        assertThatThrownBy(() -> orderService.cancelOrder(order))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testCannotPlaceAlreadyPlacedOrder() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-A", 5, 20.0));
        orderService.placeOrder(order);
        assertThatThrownBy(() -> orderService.placeOrder(order))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testMultiItemOrderPricing() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-A", 3, 20.0));
        order.addItem(new OrderItem("PROD-B", 2, 30.0));
        double total = orderService.placeOrder(order);
        assertThat(total).isEqualTo(120.0);
    }
}
