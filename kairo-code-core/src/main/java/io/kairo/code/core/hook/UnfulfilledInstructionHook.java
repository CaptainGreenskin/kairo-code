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
import io.kairo.api.hook.PreCompleteEvent;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans the original task instructions for "Create ...Test.java" requirements and injects a
 * reminder if any required test files do not yet exist.
 *
 * <p>Borrowing from claude-code-best generalPurposeAgent.ts: "Complete the task fully—don't
 * gold-plate, but don't leave it half-done."
 *
 * <p>Detection pattern: lines containing both "Create" and ".java" in the task text. Checks file
 * existence against workingDir. Fires at most once per missing file, up to MAX_INJECTIONS total.
 *
 * <p>Phase: {@link HookPhase#PRE_COMPLETE} — fires when model is about to return a final answer
 * (no tool calls). Returning INJECT forces another iteration, analogous to claude-code's
 * preventContinuation. REPL mode: disabled.
 */
public final class UnfulfilledInstructionHook {

    private static final int MAX_INJECTIONS = 3;

    private static final String INJECT_TEMPLATE =
            "Required file not yet created: {0}\n"
                    + "The task explicitly requires you to create this file. Create it now before"
                    + " finishing.";

    private static final Pattern CREATE_JAVA_PATTERN =
            Pattern.compile("Create\\s+[`']?(src/test/[^\\s`']+\\.java)");

    private final String workingDir;
    private final boolean isRepl;
    private int injectionCount;

    public UnfulfilledInstructionHook(String workingDir) {
        this(workingDir, false);
    }

    public UnfulfilledInstructionHook(String workingDir, boolean isRepl) {
        this.workingDir = workingDir;
        this.isRepl = isRepl;
    }

    @HookHandler(HookPhase.PRE_COMPLETE)
    public HookResult<PreCompleteEvent> onPreComplete(PreCompleteEvent event) {
        if (isRepl || workingDir == null || workingDir.isBlank()) {
            return HookResult.proceed(event);
        }
        if (injectionCount >= MAX_INJECTIONS) {
            return HookResult.proceed(event);
        }

        List<String> paths = extractCreatePaths(event.conversationHistory());
        for (String path : paths) {
            Path fullPath = Path.of(workingDir, path);
            if (Files.exists(fullPath)) {
                continue; // already created
            }
            injectionCount++;
            String message = INJECT_TEMPLATE.replace("{0}", path);
            return HookResult.inject(
                    event, Msg.of(MsgRole.USER, message), "UnfulfilledInstructionHook");
        }

        return HookResult.proceed(event);
    }

    /** Extract "Create src/test/...*.java" paths from the first USER message in history. */
    static List<String> extractCreatePaths(List<Msg> messages) {
        for (Msg msg : messages) {
            if (msg.role() == MsgRole.USER) {
                String text = extractText(msg);
                if (text != null && !text.isBlank()) {
                    return text.lines()
                            .map(
                                    line -> {
                                        Matcher m = CREATE_JAVA_PATTERN.matcher(line);
                                        return m.find() ? m.group(1) : null;
                                    })
                            .filter(p -> p != null)
                            .toList();
                }
            }
        }
        return List.of();
    }

    private static String extractText(Msg msg) {
        StringBuilder sb = new StringBuilder();
        for (Content c : msg.contents()) {
            if (c instanceof Content.TextContent text) {
                sb.append(text.text());
            }
        }
        return sb.toString();
    }
}
