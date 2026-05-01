package io.kairo.code.service.concurrency;

/**
 * RAII guard for agent execution slots.
 * Acquire via {@link AgentConcurrencyController#acquire(String)};
 * always close in try-with-resources.
 */
public final class AgentSlot implements AutoCloseable {

    private final String sessionId;
    private final Runnable releaseAction;
    private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean(false);

    AgentSlot(String sessionId, Runnable releaseAction) {
        this.sessionId = sessionId;
        this.releaseAction = releaseAction;
    }

    public String sessionId() {
        return sessionId;
    }

    @Override
    public void close() {
        if (released.compareAndSet(false, true)) {
            releaseAction.run();
        }
    }
}
