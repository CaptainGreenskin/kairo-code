package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingEngineTest {

    private PricingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PricingEngine();
    }

    @Test
    void testCalculateTotalSingleItem() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-001", 2, 10.0));
        assertThat(engine.calculateTotal(order)).isEqualTo(20.0);
    }

    @Test
    void testCalculateTotalMultipleItems() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-001", 2, 10.0));
        order.addItem(new OrderItem("PROD-002", 3, 5.0));
        assertThat(engine.calculateTotal(order)).isEqualTo(35.0);
    }

    @Test
    void testCalculateTotalZeroQuantity() {
        Order order = new Order("ORD-001", "CUST-001");
        order.addItem(new OrderItem("PROD-001", 0, 10.0));
        assertThat(engine.calculateTotal(order)).isZero();
    }

    @Test
    void testCalculateTotalNullOrder() {
        // Null order should return zero, not throw NPE
        assertThat(engine.calculateTotal(null)).isZero();
    }

    @Test
    void testApplyDiscountNoDiscount() {
        assertThat(engine.applyDiscount(100.0, 0)).isEqualTo(100.0);
    }

    @Test
    void testApplyDiscountTenPercent() {
        // 10% off 100 = 90
        assertThat(engine.applyDiscount(100.0, 10)).isEqualTo(90.0);
    }

    @Test
    void testApplyDiscountTwentyFivePercent() {
        // 25% off 200 = 150
        assertThat(engine.applyDiscount(200.0, 25)).isEqualTo(150.0);
    }

    @Test
    void testApplyDiscountFullDiscount() {
        assertThat(engine.applyDiscount(50.0, 100)).isZero();
    }
}
