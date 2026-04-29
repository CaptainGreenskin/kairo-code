package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderServiceTest {

    private OrderService service;
    private InventoryReserver reserver;

    @BeforeEach
    void setUp() {
        OrderQueue queue = new OrderQueue(10);
        OrderProcessor processor = new OrderProcessor();
        PaymentLedger ledger = new PaymentLedger();
        reserver = new InventoryReserver();
        reserver.initProduct("p1", 100);

        service = new OrderService(queue, processor, ledger, reserver);
    }

    @Test
    void enqueueAddsToQueue() {
        Order order = new Order("o1", "p1", 1, 10.0);
        boolean ok = service.enqueue(order);

        assertThat(ok).isTrue();
        assertThat(service.queue().size()).isEqualTo(1);
    }

    @Test
    void dequeueRemovesFromQueue() {
        Order order = new Order("o1", "p1", 1, 10.0);
        service.enqueue(order);

        Order dequeued = service.dequeue();

        assertThat(dequeued).isEqualTo(order);
        assertThat(service.queue().isEmpty()).isTrue();
    }

    @Test
    void completeOrderRecordsPayment() {
        Order order = new Order("o1", "p1", 2, 20.0);
        service.completeOrder(order);

        assertThat(service.ledger().getPayment("o1")).hasValue(20.0);
    }

    @Test
    void completeOrderCompletesStatus() {
        Order order = new Order("o1", "p1", 1, 10.0);
        service.completeOrder(order);

        assertThat(order.status()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void cancelOrderCancelsStatus() {
        Order order = new Order("o1", "p1", 1, 10.0);
        service.cancelOrder(order);

        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelOrderReleasesReservation() {
        reserver.reserve("p1", 10);
        Order order = new Order("o1", "p1", 10, 10.0);
        service.cancelOrder(order);

        assertThat(reserver.available("p1")).isEqualTo(100);
        assertThat(reserver.reserved("p1")).isZero();
    }

    @Test
    void completeOrderReleaseMatchesQuantity() {
        reserver.reserve("p1", 30);
        Order order = new Order("o1", "p1", 30, 30.0);
        service.completeOrder(order);

        assertThat(reserver.available("p1")).isEqualTo(100);
        assertThat(reserver.total("p1")).isEqualTo(100);
    }

    @Test
    void ledgerGrandTotalMatchesCompletedOrders() {
        for (int i = 0; i < 3; i++) {
            service.completeOrder(new Order("o" + i, "p1", 1, 10.0));
        }

        assertThat(service.ledger().grandTotal()).isEqualTo(30.0);
    }

    @Test
    void completeOrderCannotBeCalledTwice() {
        Order order = new Order("o1", "p1", 1, 10.0);
        service.completeOrder(order);

        assertThatThrownBy(() -> service.completeOrder(order))
                .isInstanceOf(IllegalStateException.class);
    }
}
