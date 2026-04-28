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
 * Warns the model when conversation context grows large, to prevent GLM-5.1 context overflow.
 *
 * <p>Estimates token count from the full message list (chars / 4) and injects warnings at two
 * thresholds. Each threshold fires only once per session.
 *
 * <p>Active in both REPL and one-shot mode — context overflow can happen in either.
 */
public final class ContextWindowGuardHook {

    private static final String WARN_MESSAGE =
            "Context is getting large. Keep responses concise and avoid repeating prior information.";

    private static final String CRITICAL_MESSAGE =
            "Context window is near capacity. Run /compact to compress history, "
                    + "or the session may degrade.";

    private final int warnThreshold;
    private final int criticalThreshold;
    private boolean warnFired;
    private boolean criticalFired;

    /** Uses default thresholds: warn at 40_000, critical at 70_000. */
    public ContextWindowGuardHook() {
        this(40_000, 70_000);
    }

    /**
     * @param warnThreshold token count that triggers the soft warning
     * @param criticalThreshold token count that triggers the urgent /compact suggestion
     */
    public ContextWindowGuardHook(int warnThreshold, int criticalThreshold) {
        this.warnThreshold = warnThreshold;
        this.criticalThreshold = criticalThreshold;
        this.warnFired = false;
        this.criticalFired = false;
    }

    @HookHandler(HookPhase.PRE_REASONING)
    public HookResult<PreReasoningEvent> onPreReasoning(PreReasoningEvent event) {
        int estimatedTokens = estimateTokens(event.messages());

        // Check critical threshold first (higher priority)
        if (estimatedTokens > criticalThreshold && !criticalFired) {
            criticalFired = true;
            warnFired = true; // also mark warn as fired so it doesn't fire after critical
            return HookResult.inject(
                    event, Msg.of(MsgRole.USER, CRITICAL_MESSAGE), "ContextWindowGuardHook");
        }

        if (estimatedTokens > warnThreshold && !warnFired) {
            warnFired = true;
            return HookResult.inject(
                    event, Msg.of(MsgRole.USER, WARN_MESSAGE), "ContextWindowGuardHook");
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
}
