package com.example;

public class OrderService {

    private final OrderQueue queue;
    private final OrderProcessor processor;
    private final PaymentLedger ledger;
    private final InventoryReserver reserver;

    public OrderService(OrderQueue queue, OrderProcessor processor,
                        PaymentLedger ledger, InventoryReserver reserver) {
        this.queue = queue;
        this.processor = processor;
        this.ledger = ledger;
        this.reserver = reserver;
    }

    public boolean enqueue(Order order) {
        return queue.offer(order);
    }

    public Order dequeue() {
        return queue.poll();
    }

    public void completeOrder(Order order) {
        reserver.release(order.productId(), order.quantity());
        ledger.record(order.id(), order.productId(), order.amount());
        order.complete();
    }

    public void cancelOrder(Order order) {
        order.cancel();
        reserver.release(order.productId(), order.quantity());
    }

    public OrderProcessor processor() { return processor; }
    public PaymentLedger ledger() { return ledger; }
    public InventoryReserver reserver() { return reserver; }
    public OrderQueue queue() { return queue; }
}
