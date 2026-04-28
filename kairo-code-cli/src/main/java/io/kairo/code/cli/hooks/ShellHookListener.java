package io.kairo.code.cli.hooks;

import io.kairo.api.hook.HookHandler;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.PostActingEvent;
import io.kairo.api.hook.PreActingEvent;
import io.kairo.api.message.Content;
import java.util.Map;

/**
 * Bridges kairo framework hook events to user-configured shell hooks.
 *
 * <p>Listens for {@link HookPhase#PRE_ACTING} and {@link HookPhase#POST_ACTING} events
 * and fires corresponding {@code PreToolUse} / {@code PostToolUse} shell hooks via {@link HookExecutor}.
 *
 * <p>Registered as a regular hook alongside {@code AgentEventPrinter} etc.
 * When no hooks are configured this class is not instantiated, so there is zero overhead.
 */
public class ShellHookListener {

    private final HookExecutor hookExecutor;

    public ShellHookListener(HookExecutor hookExecutor) {
        this.hookExecutor = hookExecutor;
    }

    @HookHandler(HookPhase.PRE_ACTING)
    public void onPreActing(PreActingEvent event) {
        String toolInput = flattenInput(event.input());
        hookExecutor.fire("PreToolUse", event.toolName(), toolInput);
    }

    @HookHandler(HookPhase.POST_ACTING)
    public void onPostActing(PostActingEvent event) {
        String result = event.result() != null ? event.result().content() : "";
        hookExecutor.fire("PostToolUse", event.toolName(), result);
    }

    /** Convenience: fire the Stop hook when the agent turn ends. */
    public void onStop() {
        hookExecutor.fire("Stop", "", "");
    }

    /** Convert the tool input map to a single string (first value, or empty). */
    private static String flattenInput(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        // Use the first non-blank value, preferring common fields like "command" or "path"
        for (String key : new String[]{"command", "path", "file", "query", "pattern"}) {
            Object val = input.get(key);
            if (val != null && !val.toString().isBlank()) {
                return val.toString();
            }
        }
        // Fallback: first value
        return input.values().iterator().next().toString();
    }
}
