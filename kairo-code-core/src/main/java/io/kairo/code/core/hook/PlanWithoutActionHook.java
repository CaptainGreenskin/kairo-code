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
import java.util.Set;

/**
 * Detects "plan-only" responses where the model wrote todos but took no implementation action.
 *
 * <p>When the model calls {@code todo_write} or {@code todo_read} but no implementation tools
 * (read_file, write_file, bash, edit_file, search_files, glob), this hook injects a corrective
 * message urging the model to start executing.
 *
 * <p>Injection is limited to 2 times to avoid infinite loops. REPL mode is excluded.
 */
public final class PlanWithoutActionHook {

    private static final int MAX_INJECTIONS = 2;

    private static final Set<String> IMPLEMENTATION_TOOLS =
            Set.of("bash", "read", "write", "edit", "grep", "glob", "git", "tree");

    private static final Set<String> PLAN_TOOLS = Set.of("todo_write", "todo_read");

    private static final String INJECT_MESSAGE =
            "You created todos but did not use any implementation tools. "
                    + "The task is not done. Immediately begin executing the todos: "
                    + "read the relevant files, make the changes, verify with tests.";

    private final int maxInjections;
    private int injectionCount;
    private final boolean isRepl;

    public PlanWithoutActionHook() {
        this(MAX_INJECTIONS, false);
    }

    /**
     * @param maxInjections maximum number of times to inject before giving up
     * @param isRepl true if running in REPL/interactive mode (suppresses injection)
     */
    public PlanWithoutActionHook(int maxInjections, boolean isRepl) {
        this.maxInjections = maxInjections;
        this.injectionCount = 0;
        this.isRepl = isRepl;
    }

    @HookHandler(HookPhase.POST_REASONING)
    public HookResult<PostReasoningEvent> onPostReasoning(PostReasoningEvent event) {
        if (isRepl) {
            return HookResult.proceed(event);
        }

        ModelResponse response = event.response();
        if (response == null || response.contents() == null) {
            return HookResult.proceed(event);
        }

        var toolCalls =
                response.contents().stream()
                        .filter(Content.ToolUseContent.class::isInstance)
                        .map(c -> (Content.ToolUseContent) c)
                        .toList();

        if (toolCalls.isEmpty()) {
            return HookResult.proceed(event);
        }

        boolean hasPlanTool = false;
        boolean hasImplementationTool = false;

        for (Content.ToolUseContent call : toolCalls) {
            String name = call.toolName();
            if (PLAN_TOOLS.contains(name)) {
                hasPlanTool = true;
            }
            if (IMPLEMENTATION_TOOLS.contains(name)) {
                hasImplementationTool = true;
            }
        }

        if (!hasPlanTool || hasImplementationTool) {
            return HookResult.proceed(event);
        }

        if (injectionCount >= maxInjections) {
            return HookResult.proceed(event);
        }

        injectionCount++;
        return HookResult.inject(
                event, Msg.of(MsgRole.USER, INJECT_MESSAGE), "PlanWithoutActionHook");
    }
}
