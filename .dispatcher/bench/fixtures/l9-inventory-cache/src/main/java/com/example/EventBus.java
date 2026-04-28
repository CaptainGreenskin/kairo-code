package com.example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Simple in-memory event bus for testing purposes.
 */
public class EventBus {
    private final List<Event> publishedEvents = new ArrayList<>();
    private final List<Subscriber> subscribers = new ArrayList<>();

    public void publish(String eventType, String productId) {
        publishedEvents.add(new Event(eventType, productId));
        for (Subscriber s : subscribers) {
            s.onEvent(eventType, productId);
        }
    }

    public void addSubscriber(Subscriber subscriber) {
        subscribers.add(subscriber);
    }

    public List<Event> getPublishedEvents() {
        return Collections.unmodifiableList(publishedEvents);
    }

    public void clear() {
        publishedEvents.clear();
    }

    public record Event(String eventType, String productId) {}

    @FunctionalInterface
    public interface Subscriber {
        void onEvent(String eventType, String productId);
    }
}
