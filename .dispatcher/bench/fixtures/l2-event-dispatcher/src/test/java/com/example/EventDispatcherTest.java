package com.example;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class EventDispatcherTest {

    // ── domain objects ──────────────────────────────────────────────────────
    record OrderPlaced(String orderId, double amount) {}
    record OrderCancelled(String orderId) {}
    record PaymentReceived(String orderId, double amount) {}

    private EventDispatcher dispatcher;

    @BeforeEach void setUp() { dispatcher = new EventDispatcher(); }

    // ── 1. basic subscribe + dispatch ───────────────────────────────────────
    @Test void singleHandlerReceivesEvent() {
        List<String> received = new ArrayList<>();
        dispatcher.subscribe(OrderPlaced.class, e -> received.add(e.orderId()));
        dispatcher.dispatch(new OrderPlaced("O1", 99.0));
        assertEquals(List.of("O1"), received);
    }

    @Test void multipleHandlersSameTypeCalled() {
        AtomicInteger count = new AtomicInteger();
        dispatcher.subscribe(OrderPlaced.class, e -> count.incrementAndGet());
        dispatcher.subscribe(OrderPlaced.class, e -> count.incrementAndGet());
        dispatcher.dispatch(new OrderPlaced("O2", 10.0));
        assertEquals(2, count.get());
    }

    @Test void handlersCalledInSubscriptionOrder() {
        List<Integer> order = new ArrayList<>();
        dispatcher.subscribe(OrderPlaced.class, e -> order.add(1));
        dispatcher.subscribe(OrderPlaced.class, e -> order.add(2));
        dispatcher.subscribe(OrderPlaced.class, e -> order.add(3));
        dispatcher.dispatch(new OrderPlaced("O3", 1.0));
        assertEquals(List.of(1, 2, 3), order);
    }

    // ── 2. type isolation ───────────────────────────────────────────────────
    @Test void handlerNotCalledForOtherType() {
        AtomicBoolean called = new AtomicBoolean(false);
        dispatcher.subscribe(OrderCancelled.class, e -> called.set(true));
        dispatcher.dispatch(new OrderPlaced("O4", 5.0));
        assertFalse(called.get());
    }

    @Test void differentTypesDispatchedIndependently() {
        List<String> log = new ArrayList<>();
        dispatcher.subscribe(OrderPlaced.class,    e -> log.add("placed:" + e.orderId()));
        dispatcher.subscribe(OrderCancelled.class, e -> log.add("cancelled:" + e.orderId()));
        dispatcher.dispatch(new OrderPlaced("O5", 20.0));
        dispatcher.dispatch(new OrderCancelled("O5"));
        assertEquals(List.of("placed:O5", "cancelled:O5"), log);
    }

    // ── 3. unsubscribe ──────────────────────────────────────────────────────
    @Test void unsubscribeRemovesHandler() {
        AtomicInteger count = new AtomicInteger();
        EventHandler<OrderPlaced> h = e -> count.incrementAndGet();
        dispatcher.subscribe(OrderPlaced.class, h);
        dispatcher.dispatch(new OrderPlaced("O6", 1.0));
        dispatcher.unsubscribe(OrderPlaced.class, h);
        dispatcher.dispatch(new OrderPlaced("O7", 1.0));
        assertEquals(1, count.get());
    }

    @Test void unsubscribeOnlyFirstMatchingReference() {
        AtomicInteger count = new AtomicInteger();
        EventHandler<OrderPlaced> h = e -> count.incrementAndGet();
        dispatcher.subscribe(OrderPlaced.class, h);
        dispatcher.subscribe(OrderPlaced.class, h);
        dispatcher.unsubscribe(OrderPlaced.class, h);
        dispatcher.dispatch(new OrderPlaced("O8", 1.0));
        assertEquals(1, count.get()); // one removed, one remains
    }

    // ── 4. error handling ───────────────────────────────────────────────────
    @Test void handlerExceptionWrappedInEventDeliveryException() {
        dispatcher.subscribe(OrderPlaced.class, e -> { throw new IllegalStateException("boom"); });
        EventDeliveryException ex = assertThrows(EventDeliveryException.class,
            () -> dispatcher.dispatch(new OrderPlaced("O9", 1.0)));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertInstanceOf(OrderPlaced.class, ex.getEvent());
    }

    @Test void subsequentHandlersNotCalledAfterException() {
        AtomicBoolean secondCalled = new AtomicBoolean(false);
        dispatcher.subscribe(OrderPlaced.class, e -> { throw new RuntimeException("fail"); });
        dispatcher.subscribe(OrderPlaced.class, e -> secondCalled.set(true));
        assertThrows(EventDeliveryException.class,
            () -> dispatcher.dispatch(new OrderPlaced("O10", 1.0)));
        assertFalse(secondCalled.get());
    }

    // ── 5. subscriberCount ─────────────────────────────────────────────────
    @Test void subscriberCountReflectsRegistrations() {
        assertEquals(0, dispatcher.subscriberCount(OrderPlaced.class));
        dispatcher.subscribe(OrderPlaced.class, e -> {});
        dispatcher.subscribe(OrderPlaced.class, e -> {});
        assertEquals(2, dispatcher.subscriberCount(OrderPlaced.class));
    }

    @Test void subscriberCountIsolatedPerType() {
        dispatcher.subscribe(OrderPlaced.class,    e -> {});
        dispatcher.subscribe(OrderCancelled.class, e -> {});
        dispatcher.subscribe(OrderCancelled.class, e -> {});
        assertEquals(1, dispatcher.subscriberCount(OrderPlaced.class));
        assertEquals(2, dispatcher.subscriberCount(OrderCancelled.class));
    }

    // ── 6. clearAll ─────────────────────────────────────────────────────────
    @Test void clearAllRemovesEverything() {
        dispatcher.subscribe(OrderPlaced.class,    e -> {});
        dispatcher.subscribe(OrderCancelled.class, e -> {});
        dispatcher.clearAll();
        assertEquals(0, dispatcher.subscriberCount(OrderPlaced.class));
        assertEquals(0, dispatcher.subscriberCount(OrderCancelled.class));
        assertTrue(dispatcher.registeredTypes().isEmpty());
    }

    // ── 7. registeredTypes ─────────────────────────────────────────────────
    @Test void registeredTypesReturnsAllSubscribedTypes() {
        dispatcher.subscribe(OrderPlaced.class,    e -> {});
        dispatcher.subscribe(OrderCancelled.class, e -> {});
        List<Class<?>> types = dispatcher.registeredTypes();
        assertTrue(types.contains(OrderPlaced.class));
        assertTrue(types.contains(OrderCancelled.class));
        assertEquals(2, types.size());
    }

    // ── 8. dispatchAsync ───────────────────────────────────────────────────
    @Test void dispatchAsyncCallsAllHandlers() throws Exception {
        AtomicInteger count = new AtomicInteger();
        Executor exec = Executors.newFixedThreadPool(2);
        dispatcher.subscribe(OrderPlaced.class, e -> count.incrementAndGet());
        dispatcher.subscribe(OrderPlaced.class, e -> count.incrementAndGet());
        CompletableFuture<Void> f = dispatcher.dispatchAsync(new OrderPlaced("O11", 1.0), exec);
        f.get(5, TimeUnit.SECONDS);
        assertEquals(2, count.get());
    }

    @Test void dispatchAsyncAllHandlersRunEvenIfOneFails() throws Exception {
        AtomicInteger count = new AtomicInteger();
        Executor exec = Executors.newFixedThreadPool(2);
        dispatcher.subscribe(OrderPlaced.class, e -> { throw new RuntimeException("async fail"); });
        dispatcher.subscribe(OrderPlaced.class, e -> count.incrementAndGet());
        CompletableFuture<Void> f = dispatcher.dispatchAsync(new OrderPlaced("O12", 1.0), exec);
        try { f.get(5, TimeUnit.SECONDS); } catch (ExecutionException ignored) {}
        // second handler must have run despite first failing
        assertEquals(1, count.get());
    }

    // ── 9. no handlers (no-op) ─────────────────────────────────────────────
    @Test void dispatchWithNoHandlersIsNoOp() {
        assertDoesNotThrow(() -> dispatcher.dispatch(new PaymentReceived("O13", 50.0)));
    }

    @Test void dispatchAsyncWithNoHandlersCompletesSuccessfully() throws Exception {
        CompletableFuture<Void> f = dispatcher.dispatchAsync(
            new PaymentReceived("O14", 1.0), Runnable::run);
        assertDoesNotThrow(() -> f.get(1, TimeUnit.SECONDS));
    }
}
