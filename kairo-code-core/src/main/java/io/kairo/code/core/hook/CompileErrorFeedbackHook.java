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
 * Detects Maven compilation errors in bash tool results and immediately
 * injects a reminder to fix them before proceeding.
 *
 * <p>Without this hook the agent may ignore compilation failures and continue
 * with unrelated edits, wasting turns and scoring zero on completion checks.
 *
 * <p>Detection keywords (any one match triggers):
 * <ul>
 *   <li>{@code "COMPILATION ERROR"}</li>
 *   <li>{@code "cannot find symbol"}</li>
 *   <li>{@code "error: incompatible types"}</li>
 *   <li>{@code "[ERROR]"} + {@code ".java:"} (error on a specific source line)</li>
 * </ul>
 *
 * <p>Fires at most {@link #MAX_INJECTIONS} times per session to prevent infinite loops.
 * REPL mode: disabled.
 *
 * <p>Phase: {@link HookPhase#TOOL_RESULT} — fires immediately after every tool execution.
 */
public final class CompileErrorFeedbackHook {

    private static final int MAX_INJECTIONS = 3;

    private static final String INJECT_MESSAGE =
            "Your code has compilation errors. Fix all compilation errors before running tests "
                    + "or attempting to complete the task. Check the error message above carefully.";

    private int injectionCount;
    private final boolean isRepl;

    public CompileErrorFeedbackHook() {
        this(false);
    }

    public CompileErrorFeedbackHook(boolean isRepl) {
        this.isRepl = isRepl;
    }

    @HookHandler(HookPhase.TOOL_RESULT)
    public HookResult<ToolResultEvent> onToolResult(ToolResultEvent event) {
        if (!"bash".equals(event.toolName())) {
            return HookResult.proceed(event);
        }

        if (isRepl) {
            return HookResult.proceed(event);
        }

        String content = event.result().content();
        if (content == null || !isCompilationError(content)) {
            return HookResult.proceed(event);
        }

        if (injectionCount >= MAX_INJECTIONS) {
            return HookResult.proceed(event);
        }

        injectionCount++;

        return HookResult.inject(
                event, Msg.of(MsgRole.USER, INJECT_MESSAGE), "CompileErrorFeedbackHook");
    }

    /** Returns true if the content matches any known compilation error pattern. */
    static boolean isCompilationError(String content) {
        if (content.contains("COMPILATION ERROR")) {
            return true;
        }
        if (content.contains("cannot find symbol")) {
            return true;
        }
        if (content.contains("error: incompatible types")) {
            return true;
        }
        if (content.contains("[ERROR]") && content.contains(".java:")) {
            return true;
        }
        return false;
    }
}
