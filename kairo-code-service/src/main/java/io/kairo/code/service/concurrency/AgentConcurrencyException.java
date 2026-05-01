package io.kairo.code.service.concurrency;

/**
 * Thrown when agent concurrency limits are exceeded.
 */
public class AgentConcurrencyException extends RuntimeException {

    public enum Reason {
        GLOBAL_LIMIT,
        SESSION_LIMIT,
        DEPTH_LIMIT
    }

    private final Reason reason;

    public AgentConcurrencyException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
