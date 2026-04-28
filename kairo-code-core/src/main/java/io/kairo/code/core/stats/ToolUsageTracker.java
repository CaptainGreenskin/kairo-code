package io.kairo.code.core.stats;

import io.kairo.api.hook.OnToolResult;
import io.kairo.api.hook.ToolResultEvent;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hook handler that tracks per-tool usage statistics: call count, success count, and total duration.
 *
 * <p>Registered as a hook in {@code CodeAgentFactory.SessionOptions}, this class receives
 * {@link ToolResultEvent} callbacks via the {@link OnToolResult} annotation and accumulates
 * statistics that can be queried via {@link #snapshot()}.
 */
public final class ToolUsageTracker {

    private final Map<String, ToolStat> stats = new ConcurrentHashMap<>();

    @OnToolResult
    public void onToolResult(ToolResultEvent event) {
        String toolName = event.toolName();
        long durationMillis = event.duration().toMillis();
        boolean success = event.success();

        stats.computeIfAbsent(toolName, k -> new ToolStat())
                .record(success, durationMillis);
    }

    /**
     * Returns an immutable snapshot of all tool statistics.
     */
    public Map<String, ToolStat> snapshot() {
        return Collections.unmodifiableMap(new ConcurrentHashMap<>(stats));
    }

    /**
     * Mutable accumulator for a single tool's statistics.
     */
    public static final class ToolStat {
        private final AtomicLong calls = new AtomicLong(0);
        private final AtomicLong successes = new AtomicLong(0);
        private final AtomicLong totalMillis = new AtomicLong(0);

        void record(boolean success, long durationMillis) {
            calls.incrementAndGet();
            if (success) {
                successes.incrementAndGet();
            }
            totalMillis.addAndGet(durationMillis);
        }

        public long calls() {
            return calls.get();
        }

        public long successes() {
            return successes.get();
        }

        public long totalMillis() {
            return totalMillis.get();
        }

        /**
         * Returns the success rate as a percentage (0.0 to 100.0).
         */
        public double successRate() {
            long c = calls.get();
            if (c == 0) return 0.0;
            return (successes.get() * 100.0) / c;
        }

        /**
         * Returns the average duration per call in milliseconds.
         */
        public long avgMillis() {
            long c = calls.get();
            if (c == 0) return 0;
            return totalMillis.get() / c;
        }
    }
}
