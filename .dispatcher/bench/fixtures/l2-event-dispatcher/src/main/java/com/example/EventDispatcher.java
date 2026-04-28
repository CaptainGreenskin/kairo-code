package com.example;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * A type-safe publish/subscribe event dispatcher.
 *
 * Rules:
 * - Handlers are matched by exact event class (no supertype matching).
 * - dispatch() calls handlers in subscription order, synchronously.
 * - If a handler throws, wrap in EventDeliveryException and rethrow immediately
 *   (remaining handlers are NOT called for that event).
 * - dispatchAsync() calls all handlers on the given executor, returns a
 *   CompletableFuture<Void> that completes when ALL handlers finish.
 *   Handler exceptions must NOT cancel other handlers — collect all results.
 * - unsubscribe() removes the first matching handler reference (by ==).
 * - subscriberCount() returns count for that exact event type.
 * - clearAll() removes all subscriptions.
 */
public class EventDispatcher {

    // TODO: add fields

    public EventDispatcher() {
        // TODO: initialize
    }

    /** Register handler for events of type eventType. */
    public <T> void subscribe(Class<T> eventType, EventHandler<T> handler) {
        // TODO: implement
        throw new UnsupportedOperationException("not implemented");
    }

    /** Remove first matching handler (by reference ==) for eventType. */
    public <T> void unsubscribe(Class<T> eventType, EventHandler<T> handler) {
        // TODO: implement
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Synchronous dispatch: call each handler in subscription order.
     * On first handler exception, throw EventDeliveryException immediately.
     */
    @SuppressWarnings("unchecked")
    public <T> void dispatch(T event) {
        // TODO: implement
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Async dispatch: submit each handler to executor independently.
     * Returns a future that completes (exceptionally with the first error) when all are done.
     * All handlers must be attempted even if earlier ones fail.
     */
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<Void> dispatchAsync(T event, Executor executor) {
        // TODO: implement
        throw new UnsupportedOperationException("not implemented");
    }

    /** Number of registered handlers for this exact event type. */
    public int subscriberCount(Class<?> eventType) {
        // TODO: implement
        throw new UnsupportedOperationException("not implemented");
    }

    /** Remove all subscriptions. */
    public void clearAll() {
        // TODO: implement
        throw new UnsupportedOperationException("not implemented");
    }

    /** All currently registered event types. */
    public List<Class<?>> registeredTypes() {
        // TODO: implement
        throw new UnsupportedOperationException("not implemented");
    }
}
