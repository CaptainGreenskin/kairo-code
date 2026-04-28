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
 * After the model calls {@code write_file} or {@code edit_file} on a {@code .java} file,
 * injects a hint urging it to run {@code mvn test} to verify the changes compile and tests pass.
 *
 * <p>Only active in headless/one-shot mode (non-REPL). REPL mode is excluded to avoid
 * interrupting the user's interactive workflow.
 */
public final class PostEditHintHook {

    private static final Set<String> EDIT_TOOLS = Set.of("write_file", "edit_file");

    private static final String INJECT_MESSAGE =
            "You just modified Java source files. Run `mvn test` to verify your changes "
                    + "compile and all tests pass. If tests fail, read the error output and fix the issues.";

    private final boolean isRepl;

    public PostEditHintHook() {
        this(false);
    }

    /**
     * @param isRepl true if running in REPL/interactive mode (suppresses injection)
     */
    public PostEditHintHook(boolean isRepl) {
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

        for (Content content : response.contents()) {
            if (content instanceof Content.ToolUseContent toolUse) {
                if (EDIT_TOOLS.contains(toolUse.toolName())) {
                    Object pathValue = toolUse.input().get("path");
                    if (pathValue instanceof String path && path.endsWith(".java")) {
                        return HookResult.inject(
                                event, Msg.of(MsgRole.USER, INJECT_MESSAGE), "PostEditHintHook");
                    }
                }
            }
        }

        return HookResult.proceed(event);
    }
}
