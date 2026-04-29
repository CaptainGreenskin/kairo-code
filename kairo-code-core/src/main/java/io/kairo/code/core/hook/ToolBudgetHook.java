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
import io.kairo.api.hook.ToolResultEvent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;

/**
 * Injects a budget-warning message when total tool calls in the session exceed a threshold.
 *
 * <p>Two thresholds (configurable via constructor or env vars):
 * <ul>
 *   <li><b>warn</b> (default 60): inject a soft warning</li>
 *   <li><b>force</b> (default 100): inject a hard stop + commit instruction</li>
 * </ul>
 *
 * <p>Each threshold fires once. REPL mode excluded.
 *
 * <p>Phase: {@link HookPhase#TOOL_RESULT} — fires after every tool call result.
 *
 * <p>Env vars:
 * <ul>
 *   <li>{@code KAIRO_CODE_TOOL_BUDGET_WARN} (default 60)</li>
 *   <li>{@code KAIRO_CODE_TOOL_BUDGET_FORCE} (default 100)</li>
 * </ul>
 */
public final class ToolBudgetHook {

    private static final String WARN_MESSAGE =
            "You have made {0} tool calls. Start wrapping up and commit progress.";

    private static final String FORCE_MESSAGE =
            "STOP. {0} tool calls used. Commit whatever is done now and finish.";

    private final int warnAt;
    private final int forceAt;
    private final boolean isRepl;
    private int callCount;
    private boolean warnFired;
    private boolean forceFired;

    /** Uses env var defaults: warn=60, force=100, not REPL. */
    public ToolBudgetHook() {
        this(false);
    }

    /**
     * @param isRepl true if running in REPL/interactive mode (suppresses all injections)
     */
    public ToolBudgetHook(boolean isRepl) {
        this(isRepl, envInt("KAIRO_CODE_TOOL_BUDGET_WARN", 60),
                envInt("KAIRO_CODE_TOOL_BUDGET_FORCE", 100));
    }

    /**
     * @param isRepl true if running in REPL/interactive mode
     * @param warnAt warn threshold (0 = disabled)
     * @param forceAt force threshold (0 = disabled)
     */
    public ToolBudgetHook(boolean isRepl, int warnAt, int forceAt) {
        this.isRepl = isRepl;
        this.warnAt = warnAt;
        this.forceAt = forceAt;
        this.callCount = 0;
        this.warnFired = false;
        this.forceFired = false;
    }

    @HookHandler(HookPhase.TOOL_RESULT)
    public HookResult<ToolResultEvent> onToolResult(ToolResultEvent event) {
        if (isRepl) {
            return HookResult.proceed(event);
        }

        callCount++;

        // Check force threshold first — it takes priority.
        if (forceAt > 0 && callCount >= forceAt && !forceFired) {
            forceFired = true;
            warnFired = true; // also suppress warn if force fires
            return HookResult.inject(
                    event,
                    Msg.of(MsgRole.USER, FORCE_MESSAGE.replace("{0}", String.valueOf(callCount))),
                    "ToolBudgetHook");
        }

        if (warnAt > 0 && callCount >= warnAt && !warnFired) {
            warnFired = true;
            return HookResult.inject(
                    event,
                    Msg.of(MsgRole.USER, WARN_MESSAGE.replace("{0}", String.valueOf(callCount))),
                    "ToolBudgetHook");
        }

        return HookResult.proceed(event);
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
