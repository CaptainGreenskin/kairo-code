/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kairo.code.core.hook;

import io.kairo.api.hook.HookHandler;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelResponse;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Detects when the agent calls the same tool in N consecutive turns.
 *
 * <p>Tracks the last-called tool name per turn. When the same tool appears
 * in {@code consecutiveTurnsThreshold} turns in a row, injects a one-time hint:
 * "You've called '{tool}' {n} times in a row. Consider a different approach."
 *
 * <p>Fires at most once per tool per session to avoid spamming.
 * REPL mode excluded.
 *
 * <p>Phase: {@link HookPhase#POST_REASONING}.
 *
 * <p>Default: 4 consecutive turns.
 * Env var: {@code KAIRO_CODE_REPETITIVE_TOOL_THRESHOLD} (default 4).
 */
public final class RepetitiveToolHook {

    private static final String INJECT_MESSAGE =
            "You've called '{0}' {1} times in a row. Consider a different approach.";

    private final int threshold;
    private final boolean isRepl;

    // Current streak: toolName → consecutive count
    private String lastTool;
    private int consecutiveCount;

    // Track which tools have already fired their injection (fire once per tool)
    private final Set<String> injectedTools;

    /** Uses env var default threshold of 4, not REPL. */
    public RepetitiveToolHook() {
        this(false);
    }

    /**
     * @param isRepl true if running in REPL/interactive mode (suppresses all injections)
     */
    public RepetitiveToolHook(boolean isRepl) {
        this(isRepl, envInt("KAIRO_CODE_REPETITIVE_TOOL_THRESHOLD", 4));
    }

    /**
     * @param isRepl true if running in REPL/interactive mode
     * @param threshold consecutive turns threshold for detection
     */
    public RepetitiveToolHook(boolean isRepl, int threshold) {
        if (threshold < 2) {
            throw new IllegalArgumentException("threshold must be >= 2, was: " + threshold);
        }
        this.isRepl = isRepl;
        this.threshold = threshold;
        this.lastTool = null;
        this.consecutiveCount = 0;
        this.injectedTools = new HashSet<>();
    }

    @HookHandler(HookPhase.POST_REASONING)
    public HookResult<PostReasoningEvent> onPostReasoning(PostReasoningEvent event) {
        if (isRepl || injectedTools.size() >= 10) {
            // Safety cap: if we've injected for 10 different tools, stop tracking
            return HookResult.proceed(event);
        }

        String toolName = extractFirstToolName(event.response());
        if (toolName == null) {
            // No tool call this turn — reset streak
            lastTool = null;
            consecutiveCount = 0;
            return HookResult.proceed(event);
        }

        // Update consecutive tracking
        if (toolName.equals(lastTool)) {
            consecutiveCount++;
        } else {
            lastTool = toolName;
            consecutiveCount = 1;
        }

        // Check threshold
        if (consecutiveCount >= threshold && injectedTools.add(toolName)) {
            String message = INJECT_MESSAGE
                    .replace("{0}", toolName)
                    .replace("{1}", String.valueOf(consecutiveCount));
            return HookResult.inject(
                    event, Msg.of(MsgRole.USER, message), "RepetitiveToolHook");
        }

        return HookResult.proceed(event);
    }

    /** Extract the first tool name from the model response's tool calls. */
    static String extractFirstToolName(ModelResponse response) {
        if (response == null || response.contents() == null) {
            return null;
        }
        for (Content content : response.contents()) {
            if (content instanceof Content.ToolUseContent toolUse) {
                return toolUse.toolName();
            }
        }
        return null;
    }

    private static int envInt(String key, int defaultValue) {
        String val = System.getenv(key);
        if (val != null && !val.isBlank()) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return defaultValue;
    }
}
