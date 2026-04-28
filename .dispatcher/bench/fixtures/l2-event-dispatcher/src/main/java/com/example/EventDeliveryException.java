package com.example;

public class EventDeliveryException extends RuntimeException {
    private final Object event;
    private final EventHandler<?> handler;

    public EventDeliveryException(Object event, EventHandler<?> handler, Throwable cause) {
        super("Handler failed for event: " + event.getClass().getSimpleName(), cause);
        this.event = event;
        this.handler = handler;
    }

    public Object getEvent() { return event; }
    public EventHandler<?> getHandler() { return handler; }
}
