package io.kairo.code.service.concurrency;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

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
