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
 * In one-shot (non-REPL) mode, detects when the agent has edited files using {@code write} or
 * {@code edit} and then received a {@code BUILD SUCCESS} from Maven, and injects a one-time
 * prompt to commit.
 *
 * <p>This prevents the agent from finishing a bug-fix task without saving the result
 * via a git commit, which hurts benchmark completion and Autonomous Verify scores.
 *
 * <p>Phase: {@link HookPhase#TOOL_RESULT} — two-stage detection:
 * <ol>
 *   <li>Any {@code write} or {@code edit} result → mark {@code hasEdited = true}</li>
 *   <li>Any {@code bash} result containing {@code BUILD SUCCESS} after edits → inject commit prompt</li>
 * </ol>
 */
public final class AutoCommitOnSuccessHook {

    private static final String COMMIT_MESSAGE =
            "All tests pass (BUILD SUCCESS). Commit your changes now:\n"
                    + "`git add -A && git commit -m '<brief description of what was fixed>'`";

    private boolean hasEdited;
    private boolean committed;

    public AutoCommitOnSuccessHook() {
        this.hasEdited = false;
        this.committed = false;
    }

    @HookHandler(HookPhase.TOOL_RESULT)
    public HookResult<ToolResultEvent> onToolResult(ToolResultEvent event) {
        // Stage 1: detect file edits (write or edit tool)
        if (isEditTool(event.toolName())) {
            hasEdited = true;
            return HookResult.proceed(event);
        }

        // Stage 2: detect BUILD SUCCESS after edits
        if ("bash".equals(event.toolName())
                && hasEdited
                && !committed
                && event.result().content() != null
                && event.result().content().contains("BUILD SUCCESS")) {
            committed = true;
            return HookResult.inject(event, Msg.of(MsgRole.USER, COMMIT_MESSAGE), "AutoCommitOnSuccessHook");
        }

        return HookResult.proceed(event);
    }

    private static boolean isEditTool(String toolName) {
        return "write".equals(toolName) || "edit".equals(toolName);
    }
}
