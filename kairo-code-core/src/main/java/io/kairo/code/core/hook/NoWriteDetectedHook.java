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
 * Detects when the model repeatedly avoids using {@code write_file} or {@code edit_file} tools
 * and instead relies on {@code bash} for file modifications.
 *
 * <p>After a configurable number of turns without any write/edit tool call, this hook injects
 * a corrective message reminding the model to use dedicated file-writing tools.
 *
 * <p>Only active in headless/one-shot mode (non-REPL). Injection is limited to once to avoid loops.
 */
public final class NoWriteDetectedHook {

    private static final int DEFAULT_THRESHOLD = 4;

    private static final Set<String> FILE_WRITE_TOOLS = Set.of("write_file", "edit_file");

    private static final String INJECT_MESSAGE =
            "CRITICAL: You have not used the write or edit tools to modify any files after "
                    + "multiple turns. The task requires you to create or modify files.\n"
                    + "You MUST call write_file or edit_file to make changes. "
                    + "Do NOT use bash echo/cat/heredoc to write files. "
                    + "Immediately read the target file, then use edit_file (or write_file for new files) "
                    + "to implement the required changes.";

    private final int threshold;
    private int turnsWithoutWrite;
    private boolean injected;
    private final boolean isRepl;

    public NoWriteDetectedHook() {
        this(DEFAULT_THRESHOLD, false);
    }

    /**
     * @param threshold number of consecutive turns without write/edit before injecting
     * @param isRepl true if running in REPL/interactive mode (suppresses injection)
     */
    public NoWriteDetectedHook(int threshold, boolean isRepl) {
        this.threshold = threshold;
        this.turnsWithoutWrite = 0;
        this.injected = false;
        this.isRepl = isRepl;
    }

    @HookHandler(HookPhase.POST_REASONING)
    public HookResult<PostReasoningEvent> onPostReasoning(PostReasoningEvent event) {
        if (isRepl || injected) {
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

        boolean hasWriteTool = toolCalls.stream()
                .anyMatch(call -> FILE_WRITE_TOOLS.contains(call.toolName()));

        if (hasWriteTool) {
            turnsWithoutWrite = 0;
            return HookResult.proceed(event);
        }

        turnsWithoutWrite++;
        if (turnsWithoutWrite >= threshold) {
            injected = true;
            return HookResult.inject(
                    event, Msg.of(MsgRole.USER, INJECT_MESSAGE), "NoWriteDetectedHook");
        }

        return HookResult.proceed(event);
    }
}
