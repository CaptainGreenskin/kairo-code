package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderProcessorTest {

    private OrderProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new OrderProcessor();
    }

    @Test
    void processPendingOrderIncrementsCount() {
        Order order = new Order("o1", "p1", 1, 10.0);
        processor.process(order);

        assertThat(processor.processedCount()).isEqualTo(1);
    }

    @Test
    void processMultiplePendingOrders() {
        for (int i = 0; i < 5; i++) {
            processor.process(new Order("o" + i, "p1", 1, 10.0));
        }
        assertThat(processor.processedCount()).isEqualTo(5);
    }

    @Test
    void processNonPendingOrderIncrementsFailed() {
        Order order = new Order("o1", "p1", 1, 10.0);
        order.complete();
        processor.process(order);

        assertThat(processor.failedCount()).isEqualTo(1);
        assertThat(processor.processedCount()).isEqualTo(0);
    }

    @Test
    void totalCountReflectsBothCounters() {
        for (int i = 0; i < 3; i++) {
            processor.process(new Order("o" + i, "p1", 1, 10.0));
        }
        for (int i = 3; i < 5; i++) {
            Order order = new Order("o" + i, "p1", 1, 10.0);
            order.complete();
            processor.process(order);
        }

        assertThat(processor.totalCount()).isEqualTo(5);
    }

    @Test
    void initialCountsAreZero() {
        assertThat(processor.processedCount()).isZero();
        assertThat(processor.failedCount()).isZero();
    }

    @Test
    void processCancelledOrderIsFailure() {
        Order order = new Order("o1", "p1", 1, 10.0);
        order.cancel();
        processor.process(order);

        assertThat(processor.failedCount()).isEqualTo(1);
    }

    @Test
    void processDoesNotMutateOrder() {
        Order order = new Order("o1", "p1", 1, 10.0);
        OrderStatus before = order.status();
        processor.process(order);

        assertThat(order.status()).isEqualTo(before);
    }

    @Test
    void processedCountMatchesProcessedOrdersExactly() {
        int n = 10;
        for (int i = 0; i < n; i++) {
            processor.process(new Order("o" + i, "p" + (i % 3), 1, 10.0));
        }
        assertThat(processor.processedCount()).isEqualTo(n);
    }
}
