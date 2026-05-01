package io.kairo.code.service.concurrency;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Three-layer concurrency protection for agent execution:
 * <ol>
 *   <li>Global semaphore — max 30 concurrent agents across all sessions</li>
 *   <li>Per-session counter — max 10 concurrent agents per session</li>
 *   <li>Nesting depth — max 3 (prevents runaway sub-agent recursion)</li>
 * </ol>
 */
@Component
public class AgentConcurrencyController {

    private static final int GLOBAL_MAX = 30;
    private static final int SESSION_MAX = 10;
    private static final int DEPTH_MAX = 3;

    private final Semaphore globalSlots = new Semaphore(GLOBAL_MAX, true);
    private final ConcurrentHashMap<String, AtomicInteger> sessionCounters = new ConcurrentHashMap<>();
    private final ThreadLocal<Integer> nestingDepth = ThreadLocal.withInitial(() -> 0);

    /**
     * Acquire a slot for the given session. Call this before spawning any agent.
     *
     * @param sessionId session identifier (use "root" for top-level calls)
     * @return AgentSlot — must be closed when the agent completes
     * @throws AgentConcurrencyException if any limit is exceeded
     */
    public AgentSlot acquire(String sessionId) {
        // 1. Depth check
        int currentDepth = nestingDepth.get();
        if (currentDepth >= DEPTH_MAX) {
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

        // 4. Increment depth
        nestingDepth.set(currentDepth + 1);

        return new AgentSlot(sessionId, () -> {
            nestingDepth.set(nestingDepth.get() - 1);
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
     * Current nesting depth on this thread.
     */
    public int currentDepth() {
        return nestingDepth.get();
    }
}
