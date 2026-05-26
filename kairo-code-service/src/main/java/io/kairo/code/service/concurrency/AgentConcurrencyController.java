package io.kairo.code.service.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Three-layer concurrency protection for agent execution:
 * <ol>
 *   <li>Global semaphore — max 30 concurrent agents across all sessions</li>
 *   <li>Per-session counter — max 10 concurrent agents per session</li>
 *   <li>Nesting depth — max 3 (prevents runaway sub-agent recursion)</li>
 * </ol>
 *
 * <p>Depth is tracked per-thread via a thread-id keyed map (NOT a ThreadLocal).
 * Reactor's {@link reactor.core.scheduler.Schedulers#boundedElastic()} can run
 * the {@code Flux.create} producer on one thread and {@code doFinally} on another;
 * a ThreadLocal would leak +1 on the producer thread and -1 on the close thread,
 * eventually pinning every bounded scheduler thread at depth=3 and rejecting all
 * new requests. The map is keyed by the <em>acquiring</em> thread's id, captured
 * at acquire time and replayed on close, so the count balances regardless of
 * which thread runs the release.</p>
 */
@Component
public class AgentConcurrencyController {

    private static final Logger log = LoggerFactory.getLogger(AgentConcurrencyController.class);

    private static final int GLOBAL_MAX = 30;
    private static final int SESSION_MAX = 10;
    private static final int DEPTH_MAX = 3;

    private final Semaphore globalSlots = new Semaphore(GLOBAL_MAX, true);
    private final ConcurrentHashMap<String, AtomicInteger> sessionCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> threadDepths = new ConcurrentHashMap<>();

    /**
     * Acquire a slot for the given session. Call this before spawning any agent.
     *
     * @param sessionId session identifier (use "root" for top-level calls)
     * @return AgentSlot — must be closed when the agent completes
     * @throws AgentConcurrencyException if any limit is exceeded
     */
    public AgentSlot acquire(String sessionId) {
        // 1. Depth check — keyed by current thread id (the acquirer)
        long tid = Thread.currentThread().getId();
        AtomicInteger depth = threadDepths.computeIfAbsent(tid, k -> new AtomicInteger(0));
        if (depth.get() >= DEPTH_MAX) {
            throw new AgentConcurrencyException(
                    AgentConcurrencyException.Reason.DEPTH_LIMIT,
                    "Max agent nesting depth " + DEPTH_MAX + " exceeded in session " + sessionId);
        }

        // 2. Per-session check
        AtomicInteger counter = sessionCounters.computeIfAbsent(sessionId, k -> new AtomicInteger(0));
        int sessionCount = counter.incrementAndGet();
        if (sessionCount > SESSION_MAX) {
            counter.decrementAndGet();
            throw new AgentConcurrencyException(
                    AgentConcurrencyException.Reason.SESSION_LIMIT,
                    "Session " + sessionId + " exceeded max concurrent agents " + SESSION_MAX);
        }

        // 3. Global check (non-blocking tryAcquire)
        if (!globalSlots.tryAcquire()) {
            counter.decrementAndGet();
            throw new AgentConcurrencyException(
                    AgentConcurrencyException.Reason.GLOBAL_LIMIT,
                    "Global agent limit " + GLOBAL_MAX + " exceeded");
        }

        // 4. Increment depth on the acquirer's bucket
        depth.incrementAndGet();
        final long acquirerTid = tid;
        final AtomicInteger acquirerDepth = depth;

        return new AgentSlot(sessionId, () -> {
            // Decrement the acquirer's depth, regardless of which thread runs close.
            // CAS-remove the bucket once it hits zero to prevent unbounded growth.
            int remainingDepth = acquirerDepth.decrementAndGet();
            if (remainingDepth <= 0) {
                threadDepths.remove(acquirerTid, acquirerDepth);
            }
            globalSlots.release();
            int remaining = counter.decrementAndGet();
            if (remaining <= 0) {
                sessionCounters.remove(sessionId, counter);
            }
        });
    }

    /**
     * Attempt to start a narrator-style auxiliary agent call without raising on contention.
     *
     * <p>Lower-priority counterpart to {@link #acquire(String)} used exclusively by the
     * experts-mode {@code NarratorDispatcher}. On success the caller's {@link Consumer} receives the
     * acquired {@link AgentSlot} and is responsible for closing it (typically in the reactive
     * chain's {@code doFinally}). On contention (any of the three limits exceeded) the dispatch is
     * silently dropped — the dispatcher's batch queue retains the events for the next attempt.
     *
     * <p>Default Agent and Team modes never call this method; it exercises zero shared mutable
     * state beyond what {@link #acquire(String)} already touches, so mode isolation is preserved.
     *
     * @param sessionId session identifier (same one passed to {@link #acquire(String)})
     * @param runWithSlot callback invoked synchronously when a slot is acquired; receives the slot
     */
    public void enqueueNarrator(String sessionId, Consumer<AgentSlot> runWithSlot) {
        AgentSlot slot;
        try {
            slot = acquire(sessionId);
        } catch (AgentConcurrencyException e) {
            log.debug("narrator.dispatch dropped session={} reason={}", sessionId, e.reason());
            return;
        }
        try {
            runWithSlot.accept(slot);
        } catch (RuntimeException e) {
            // Callback failed before installing the reactive chain that would have closed the slot.
            // Close it here to avoid leaking; the dispatcher's batch stays queued for retry.
            slot.close();
            log.warn("narrator.dispatch callback threw session={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Current global active agent count (for monitoring).
     */
    public int globalActiveCount() {
        return GLOBAL_MAX - globalSlots.availablePermits();
    }

    /**
     * Current active agent count for a session.
     */
    public int sessionActiveCount(String sessionId) {
        AtomicInteger counter = sessionCounters.get(sessionId);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Current nesting depth on this thread (i.e., agents currently nested in
     * this thread's call stack).
     */
    public int currentDepth() {
        AtomicInteger d = threadDepths.get(Thread.currentThread().getId());
        return d != null ? d.get() : 0;
    }
}
