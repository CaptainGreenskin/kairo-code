package io.kairo.code.core.evolution;

import io.kairo.api.hook.OnToolResult;
import io.kairo.api.hook.ToolResultEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Hook handler that tracks consecutive tool failures per tool name.
 *
 * <p>When a tool fails {@link #STRIKE_THRESHOLD} times in a row, the {@code onStrike3} callback is
 * invoked with the failing tool name. The counter resets on any successful execution.
 */
public final class FailurePatternTracker {

    static final int STRIKE_THRESHOLD = 3;

    private final Map<String, AtomicInteger> consecutiveFailures = new ConcurrentHashMap<>();
    private final Consumer<String> onStrike3;

    public FailurePatternTracker(Consumer<String> onStrike3) {
        this.onStrike3 = onStrike3;
    }

    @OnToolResult
    public void onToolResult(ToolResultEvent event) {
        String toolName = event.toolName();
        if (event.success()) {
            consecutiveFailures.remove(toolName);
        } else {
            AtomicInteger counter =
                    consecutiveFailures.computeIfAbsent(toolName, k -> new AtomicInteger(0));
            int count = counter.incrementAndGet();
            if (count == STRIKE_THRESHOLD) {
                counter.set(0);
                onStrike3.accept(toolName);
            }
        }
    }

    /** Returns the current consecutive failure count for a tool (for testing). */
    int consecutiveFailureCount(String toolName) {
        AtomicInteger c = consecutiveFailures.get(toolName);
        return c == null ? 0 : c.get();
    }
}
