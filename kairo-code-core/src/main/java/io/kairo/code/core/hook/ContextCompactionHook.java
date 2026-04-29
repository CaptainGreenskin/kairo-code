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
 * When the conversation context approaches the configured token limit (default 80% of
 * {@code maxContextTokens}), injects a compaction request so the model summarizes
 * all work done so far and can continue without blowing the context window.
 *
 * <p>Supports multiple compaction rounds: after each injection, a cooling period of
 * {@code coolingTurns} (default 5) turns is enforced before the next compaction can trigger.
 * This prevents rapid-fire injections while allowing long tasks to compact repeatedly.
 *
 * <p>Active only in headless/one-shot mode (non-REPL).
 *
 * <p>Configurable via environment variables:
 * <ul>
 *   <li>{@code KAIRO_CODE_MAX_CONTEXT_TOKENS} — total context capacity (default 100000)</li>
 *   <li>{@code KAIRO_CODE_COMPACTION_THRESHOLD} — fraction that triggers compaction (default 0.80)</li>
 * </ul>
 */
public final class ContextCompactionHook {

    private static final String COMPACT_MESSAGE =
            "The conversation context is getting large. "
                    + "Please summarize all work done so far — including what has been tried, "
                    + "what succeeded, what failed, and the current state of the task — in a "
                    + "concise summary (around 500 words). Then continue working on the task "
                    + "from where you left off.";

    private static final int DEFAULT_COOLING_TURNS = 5;

    private final int maxContextTokens;
    private final double threshold;
    private final boolean isRepl;
    private final int coolingTurns;
    private int turnsSinceLastCompaction;
    private int compactionCount;

    /**
     * Uses defaults from environment variables or hard-coded fallbacks:
     * maxContextTokens=100000, threshold=0.80, non-REPL, coolingTurns=5.
     */
    public ContextCompactionHook() {
        this(readMaxContextTokens(), readCompactionThreshold(), false);
    }

    /**
     * @param maxContextTokens total context capacity
     * @param threshold fraction of maxContextTokens that triggers compaction (e.g. 0.80)
     * @param isRepl true if running in REPL/interactive mode (suppresses injection)
     */
    public ContextCompactionHook(int maxContextTokens, double threshold, boolean isRepl) {
        this(maxContextTokens, threshold, isRepl, DEFAULT_COOLING_TURNS);
    }

    /**
     * @param maxContextTokens total context capacity
     * @param threshold fraction of maxContextTokens that triggers compaction
     * @param isRepl true if running in REPL/interactive mode (suppresses injection)
     * @param coolingTurns minimum turns between consecutive compactions
     */
    public ContextCompactionHook(
            int maxContextTokens, double threshold, boolean isRepl, int coolingTurns) {
        this.maxContextTokens = maxContextTokens;
        this.threshold = threshold;
        this.isRepl = isRepl;
        this.coolingTurns = coolingTurns;
        // Initialize to coolingTurns so the first compaction fires immediately on threshold breach.
        this.turnsSinceLastCompaction = coolingTurns;
        this.compactionCount = 0;
    }

    @HookHandler(HookPhase.PRE_REASONING)
    public HookResult<PreReasoningEvent> onPreReasoning(PreReasoningEvent event) {
        if (isRepl) {
            return HookResult.proceed(event);
        }

        turnsSinceLastCompaction++;

        int estimatedTokens = estimateTokens(event.messages());
        int triggerAt = (int) (maxContextTokens * threshold);

        if (estimatedTokens >= triggerAt && turnsSinceLastCompaction >= coolingTurns) {
            turnsSinceLastCompaction = 0;
            compactionCount++;
            return HookResult.inject(
                    event, Msg.of(MsgRole.USER, COMPACT_MESSAGE), "ContextCompactionHook");
        }

        return HookResult.proceed(event);
    }

    /** Returns the number of times compaction has been triggered in this session. */
    public int getCompactionCount() {
        return compactionCount;
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
        return 0.80;
    }
}
