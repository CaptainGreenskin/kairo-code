package com.example;

import java.time.Instant;
import java.util.Objects;

public class Request {

    public enum Priority {
        LOW, MEDIUM, HIGH
    }

    private final String id;
    private final String clientId;
    private final Priority priority;
    private final Instant timestamp;
    private final String payload;

    public Request(String id, String clientId, Priority priority, Instant timestamp, String payload) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.clientId = Objects.requireNonNull(clientId, "clientId must not be null");
        this.priority = Objects.requireNonNull(priority, "priority must not be null");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
        this.payload = payload;
    }

    public String getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public Priority getPriority() {
        return priority;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getPayload() {
        return payload;
    }

    @Override
    public String toString() {
        return "Request{id='%s', clientId='%s', priority=%s, payload='%s'}"
                .formatted(id, clientId, priority, payload);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Request request = (Request) o;
        return id.equals(request.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
