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
 * Detects when the agent runs only a single test class (e.g. mvn test -Dtest=RateLimiterTest)
 * rather than the full test suite, and injects a reminder to run 'mvn test'.
 *
 * <p>Detection: bash result contains exactly ONE "Tests run:" line (single class output).
 * Excludes lines that contain "[INFO] Tests run:" summary at the end of a full run.
 *
 * <p>Fires once per session to avoid spam.
 * REPL mode: disabled.
 *
 * <p>Phase: {@link HookPhase#TOOL_RESULT} — fires immediately after every tool execution.
 */
public final class FullTestSuiteHook {

    private static final String INJECT_MESSAGE =
            "You ran only a single test class. Run `mvn test` to verify the complete test suite "
                    + "— other test classes may still be failing.";

    private boolean fired;
    private final boolean isRepl;

    public FullTestSuiteHook() {
        this(false);
    }

    public FullTestSuiteHook(boolean isRepl) {
        this.isRepl = isRepl;
    }

    @HookHandler(HookPhase.TOOL_RESULT)
    public HookResult<ToolResultEvent> onToolResult(ToolResultEvent event) {
        if (isRepl || !"bash".equals(event.toolName()) || fired) {
            return HookResult.proceed(event);
        }

        String content = event.result().content();
        if (content == null) {
            return HookResult.proceed(event);
        }

        long testsRunLines = content.lines()
                .filter(line -> line.contains("Tests run:"))
                .count();

        // Exactly one "Tests run:" line = single-class partial run.
        // Zero or multiple = no tests or full suite.
        if (testsRunLines == 1) {
            fired = true;
            return HookResult.inject(
                    event, Msg.of(MsgRole.USER, INJECT_MESSAGE), "FullTestSuiteHook");
        }

        return HookResult.proceed(event);
    }
}
