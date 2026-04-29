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
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.code.core.stats.TurnMetricsCollector;

/**
 * Prevents infinite loops in one-shot (non-REPL) sessions by injecting wrap-up directives
 * when the turn count exceeds configured thresholds.
 *
 * <p>Two thresholds:
 * <ul>
 *   <li><b>warn</b> (default 20 turns): injects a soft wrap-up hint</li>
 *   <li><b>force</b> (default 30 turns): injects a hard stop with commit instruction</li>
 * </ul>
 *
 * <p>Each threshold fires exactly once. REPL mode is excluded.
 */
public final class MaxTurnsGuardHook {

    private static final String WARN_MESSAGE =
            "You have used {0} turns. Start wrapping up: commit any completed changes"
                    + " and stop planning new work.";

    private static final String FORCE_MESSAGE =
            "STOP. You have used {0} turns \u2014 maximum allowed. Commit whatever is done"
                    + " now with `git add -A && git commit -m '...'` and finish.";

    private final TurnMetricsCollector metrics;
    private final int warnAt;
    private final int forceAt;
    private boolean warnFired;
    private boolean forceFired;

    public MaxTurnsGuardHook(TurnMetricsCollector metrics) {
        this(metrics, 20, 30);
    }

    public MaxTurnsGuardHook(TurnMetricsCollector metrics, int warnAt, int forceAt) {
        this.metrics = metrics;
        this.warnAt = warnAt;
        this.forceAt = forceAt;
        this.warnFired = false;
        this.forceFired = false;
    }

    @HookHandler(HookPhase.POST_REASONING)
    public HookResult<PostReasoningEvent> onPostReasoning(PostReasoningEvent event) {
        int turns = metrics.totalTurns();

        // Check force threshold first — it takes priority.
        if (turns >= forceAt && !forceFired) {
            forceFired = true;
            warnFired = true; // also suppress warn if force fires
            return HookResult.inject(
                    event,
                    Msg.of(MsgRole.USER, FORCE_MESSAGE.replace("{0}", String.valueOf(turns))),
                    "MaxTurnsGuardHook");
        }

        if (turns >= warnAt && !warnFired) {
            warnFired = true;
            return HookResult.inject(
                    event,
                    Msg.of(MsgRole.USER, WARN_MESSAGE.replace("{0}", String.valueOf(turns))),
                    "MaxTurnsGuardHook");
        }

        return HookResult.proceed(event);
    }
}
