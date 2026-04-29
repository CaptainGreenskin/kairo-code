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
 * After the model edits Java files without running {@code mvn test} for 2 consecutive turns,
 * injects a forceful "Run mvn test NOW" directive.
 *
 * <p>This prevents the agent from making multiple file edits and moving on without verification,
 * which was the root cause of the Cache.sizeAfterExpiry benchmark failure (M14).
 *
 * <p>Only active in headless/one-shot mode (non-REPL). REPL mode is excluded to avoid
 * interrupting the user's interactive workflow.
 */
public final class PostBatchEditVerifyHook {

    private static final int MAX_INJECTIONS = 3;

    private static final Set<String> EDIT_TOOLS = Set.of("write", "edit");

    private static final String INJECT_MESSAGE =
            "You have made edits to Java files but have not run any verification command. "
                    + "Run `mvn test` NOW to check if your changes are correct. "
                    + "Do not make more edits until you see the test results.";

    /**
     * Tracks how many reasoning turns have passed since a Java edit without bash verification.
     * Set to 1 when an edit occurs; incremented each subsequent idle turn; reset on bash or new edit.
     */
    private int turnsSinceEdit;

    private int injectionCount;
    private final boolean isRepl;

    public PostBatchEditVerifyHook() {
        this(false);
    }

    /**
     * @param isRepl true if running in REPL/interactive mode (suppresses injection)
     */
    public PostBatchEditVerifyHook(boolean isRepl) {
        this.isRepl = isRepl;
    }

    @HookHandler(HookPhase.POST_REASONING)
    public HookResult<PostReasoningEvent> onPostReasoning(PostReasoningEvent event) {
        if (isRepl) {
            return HookResult.proceed(event);
        }

        ModelResponse response = event.response();
        boolean hasBash = false;
        boolean hasJavaEdit = false;

        if (response != null && response.contents() != null) {
            for (Content content : response.contents()) {
                if (content instanceof Content.ToolUseContent toolUse) {
                    String toolName = toolUse.toolName();
                    if ("bash".equals(toolName)) {
                        hasBash = true;
                    }
                    if (EDIT_TOOLS.contains(toolName)) {
                        Object pathValue = toolUse.input().get("path");
                        if (pathValue instanceof String path && path.endsWith(".java")) {
                            hasJavaEdit = true;
                        }
                    }
                }
            }
        }

        if (hasBash) {
            // Verification happened — reset.
            turnsSinceEdit = 0;
            return HookResult.proceed(event);
        }

        if (hasJavaEdit) {
            // Started a new edit batch — mark that edits occurred.
            turnsSinceEdit = 1;
            return HookResult.proceed(event);
        }

        // No bash and no edit this turn — increment counter if we have pending edits.
        if (turnsSinceEdit > 0) {
            turnsSinceEdit++;
        }

        if (turnsSinceEdit >= 2 && injectionCount < MAX_INJECTIONS) {
            injectionCount++;
            turnsSinceEdit = 0; // reset so we don't re-inject every turn
            return HookResult.inject(
                    event, Msg.of(MsgRole.USER, INJECT_MESSAGE), "PostBatchEditVerifyHook");
        }

        return HookResult.proceed(event);
    }
}
