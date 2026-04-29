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
import io.kairo.api.hook.PostActingEvent;
import io.kairo.api.hook.PreCompleteEvent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.util.Set;

/**
 * Prevents GLM-5.1 from terminating a session with zero file changes.
 *
 * <p>When the agent reaches PRE_COMPLETE without having called {@code write} or {@code edit}
 * at least once, this hook injects a forceful reminder to use file-writing tools.
 * Limited to 3 injections to avoid infinite loops.
 *
 * <p>Only active in headless/one-shot mode (non-REPL).
 */
public final class TextOnlyStallHook {

    private static final int MAX_INJECTIONS = 3;
    private static final Set<String> FILE_WRITE_TOOLS = Set.of("write", "edit");

    private static final String INJECT_MESSAGE =
            "STOP. You have made zero file changes so far. The task requires code implementation.\n"
            + "Do not narrate or plan. Immediately:\n"
            + "1. Use `read` to read the target file(s)\n"
            + "2. Use `edit` (for existing files) or `write` (for new files) to implement the changes\n"
            + "3. Use `bash` to run `mvn test -q` to verify\n\n"
            + "You MUST call tools in your next response. Text-only responses are not acceptable.";

    private final boolean isRepl;
    private int fileWriteCount;
    private int stallInjections;

    public TextOnlyStallHook() {
        this(false);
    }

    public TextOnlyStallHook(boolean isRepl) {
        this.isRepl = isRepl;
        this.fileWriteCount = 0;
        this.stallInjections = 0;
    }

    @HookHandler(HookPhase.POST_ACTING)
    public HookResult<PostActingEvent> onPostActing(PostActingEvent event) {
        if (FILE_WRITE_TOOLS.contains(event.toolName())) {
            fileWriteCount++;
        }
        return HookResult.proceed(event);
    }

    @HookHandler(HookPhase.PRE_COMPLETE)
    public HookResult<PreCompleteEvent> onPreComplete(PreCompleteEvent event) {
        if (isRepl || fileWriteCount > 0 || stallInjections >= MAX_INJECTIONS) {
            return HookResult.proceed(event);
        }
        stallInjections++;
        return HookResult.inject(event, Msg.of(MsgRole.USER, INJECT_MESSAGE), "TextOnlyStallHook");
    }
}
