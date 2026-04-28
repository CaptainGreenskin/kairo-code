package io.kairo.code.core.evolution;

import io.kairo.api.hook.OnToolResult;
import io.kairo.api.hook.ToolResultEvent;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Hook handler that tracks consecutive tool failures per tool name.
 *
 * <p>When a tool fails {@link #STRIKE_THRESHOLD} times in a row, the {@code onStrike3} callback is
 * invoked with a {@link ToolStrikeEvent} containing the tool name and recent error messages.
 * The counter resets on any successful execution.
 */
public final class FailurePatternTracker {

    static final int STRIKE_THRESHOLD = 3;
    static final int MAX_RECENT_ERRORS = 3;

    private final Map<String, AtomicInteger> consecutiveFailures = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<String>> recentErrors = new ConcurrentHashMap<>();
    private final Consumer<ToolStrikeEvent> onStrike3;

    public FailurePatternTracker(Consumer<ToolStrikeEvent> onStrike3) {
        this.onStrike3 = onStrike3;
    }

    @OnToolResult
    public void onToolResult(ToolResultEvent event) {
        String toolName = event.toolName();
        if (event.success()) {
            consecutiveFailures.remove(toolName);
            recentErrors.remove(toolName);
        } else {
            AtomicInteger counter =
                    consecutiveFailures.computeIfAbsent(toolName, k -> new AtomicInteger(0));
            int count = counter.incrementAndGet();

            ArrayDeque<String> errors =
                    recentErrors.computeIfAbsent(toolName, k -> new ArrayDeque<>(MAX_RECENT_ERRORS));
            errors.addLast(event.result().content());
            while (errors.size() > MAX_RECENT_ERRORS) {
                errors.removeFirst();
            }

            if (count == STRIKE_THRESHOLD) {
                counter.set(0);
                onStrike3.accept(new ToolStrikeEvent(toolName, List.copyOf(errors)));
            }
        }
    }

    /** Returns the current consecutive failure count for a tool (for testing). */
    int consecutiveFailureCount(String toolName) {
        AtomicInteger c = consecutiveFailures.get(toolName);
        return c == null ? 0 : c.get();
    }

    /** Returns the recent error messages for a tool (for testing). */
    List<String> recentErrorsFor(String toolName) {
        ArrayDeque<String> errors = recentErrors.get(toolName);
        return errors == null ? List.of() : List.copyOf(errors);
    }
}
