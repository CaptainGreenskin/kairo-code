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
import io.kairo.api.hook.PreReasoningEvent;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.util.List;

/**
 * When the conversation context approaches the configured token limit (default 85% of
 * {@code maxContextTokens}), injects a compaction request so the model summarizes
 * all work done so far and can continue without blowing the context window.
 *
 * <p>Replaces the old "abort on overflow" behavior with a "summarize-and-continue"
 * strategy, enabling long-running tasks (L9+ difficulty) to survive.
 *
 * <p>Active only in headless/one-shot mode (non-REPL). Fires at most once per session.
 *
 * <p>Configurable via environment variables:
 * <ul>
 *   <li>{@code KAIRO_CODE_MAX_CONTEXT_TOKENS} — total context capacity (default 100000)</li>
 *   <li>{@code KAIRO_CODE_COMPACTION_THRESHOLD} — fraction that triggers compaction (default 0.85)</li>
 * </ul>
 */
public final class ContextCompactionHook {

    private static final String COMPACT_MESSAGE =
            "The conversation context is getting large. "
                    + "Please summarize all work done so far — including what has been tried, "
                    + "what succeeded, what failed, and the current state of the task — in a "
                    + "concise summary (around 500 words). Then continue working on the task "
                    + "from where you left off.";

    private final int maxContextTokens;
    private final double threshold;
    private final boolean isRepl;
    private boolean fired;

    /**
     * Uses defaults from environment variables or hard-coded fallbacks:
     * maxContextTokens=100000, threshold=0.85, non-REPL.
     */
    public ContextCompactionHook() {
        this(readMaxContextTokens(), readCompactionThreshold(), false);
    }

    /**
     * @param maxContextTokens total context capacity
     * @param threshold fraction of maxContextTokens that triggers compaction (e.g. 0.85)
     * @param isRepl true if running in REPL/interactive mode (suppresses injection)
     */
    public ContextCompactionHook(int maxContextTokens, double threshold, boolean isRepl) {
        this.maxContextTokens = maxContextTokens;
        this.threshold = threshold;
        this.isRepl = isRepl;
        this.fired = false;
    }

    @HookHandler(HookPhase.PRE_REASONING)
    public HookResult<PreReasoningEvent> onPreReasoning(PreReasoningEvent event) {
        if (isRepl || fired) {
            return HookResult.proceed(event);
        }

        int estimatedTokens = estimateTokens(event.messages());
        int triggerAt = (int) (maxContextTokens * threshold);

        if (estimatedTokens >= triggerAt) {
            fired = true;
            return HookResult.inject(
                    event, Msg.of(MsgRole.USER, COMPACT_MESSAGE), "ContextCompactionHook");
        }

        return HookResult.proceed(event);
    }

    /** Estimates tokens as total character count across all message content, divided by 4. */
    int estimateTokens(List<Msg> messages) {
        int totalChars = 0;
        for (Msg msg : messages) {
            for (Content c : msg.contents()) {
                if (c instanceof Content.TextContent text) {
                    totalChars += text.text().length();
                } else if (c instanceof Content.ToolUseContent toolUse) {
                    totalChars += toolUse.input().toString().length();
                } else if (c instanceof Content.ToolResultContent toolResult) {
                    totalChars += toolResult.content().length();
                } else if (c instanceof Content.ThinkingContent thinking) {
                    totalChars += thinking.thinking().length();
                }
            }
        }
        return totalChars / 4;
    }

    private static int readMaxContextTokens() {
        String env = System.getenv("KAIRO_CODE_MAX_CONTEXT_TOKENS");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return 100_000;
    }

    private static double readCompactionThreshold() {
        String env = System.getenv("KAIRO_CODE_COMPACTION_THRESHOLD");
        if (env != null && !env.isBlank()) {
            try {
                return Double.parseDouble(env.trim());
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return 0.85;
    }
}
