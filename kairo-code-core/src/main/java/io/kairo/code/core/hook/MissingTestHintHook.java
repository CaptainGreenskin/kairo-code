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
 * Detects when {@code mvn test} reports zero tests run and prompts the agent to create
 * missing test classes.
 *
 * <p>Without this hint the agent may fix bugs but forget to create the test skeleton that
 * the task requires. This hook fires once per session.
 *
 * <p>Phase: {@link HookPhase#TOOL_RESULT} — fires immediately after every tool execution.
 */
public final class MissingTestHintHook {

    private static final String HINT_MESSAGE =
            "Some test classes may be missing. Check if any required test files are absent.\n"
                    + "If a test class is missing, create it now with the required test methods.\n"
                    + "Use JUnit 5 (@Test, assertThrows, assertEquals) and run `mvn test` to verify.";

    private boolean hinted;

    @HookHandler(HookPhase.TOOL_RESULT)
    public HookResult<ToolResultEvent> onToolResult(ToolResultEvent event) {
        if (!"bash".equals(event.toolName()) || hinted) {
            return HookResult.proceed(event);
        }

        String content = event.result().content();
        if (content == null) {
            return HookResult.proceed(event);
        }

        if (content.contains("Tests run: 0")
                || content.contains("No tests found")
                || content.contains("No runnable methods")) {
            hinted = true;
            return HookResult.inject(event, Msg.of(MsgRole.USER, HINT_MESSAGE), "MissingTestHintHook");
        }

        return HookResult.proceed(event);
    }
}
