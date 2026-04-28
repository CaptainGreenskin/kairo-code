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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Detects when the agent repeatedly reads the same file and injects a warning to discourage
 * redundant reads, improving token efficiency.
 *
 * <p>Tracks {@code read} tool calls per file path (via {@code result.metadata().get("path")}).
 * When a file has been read {@code threshold} times, a one-time USER message is injected telling
 * the model it already has this file's content in context.
 *
 * <p>Phase: {@link HookPhase#TOOL_RESULT}.
 */
public final class StaleReadDetectorHook {

    private final int threshold;
    private final Map<String, Integer> readCounts;
    private final Set<String> warnedFiles;

    /** Uses default threshold of 3 reads before warning. */
    public StaleReadDetectorHook() {
        this(3);
    }

    /**
     * @param threshold number of reads of the same file before injecting a warning
     */
    public StaleReadDetectorHook(int threshold) {
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be >= 1, was: " + threshold);
        }
        this.threshold = threshold;
        this.readCounts = new java.util.HashMap<>();
        this.warnedFiles = new HashSet<>();
    }

    @HookHandler(HookPhase.TOOL_RESULT)
    public HookResult<ToolResultEvent> onToolResult(ToolResultEvent event) {
        if (!"read".equals(event.toolName())) {
            return HookResult.proceed(event);
        }

        String path = extractPath(event);
        if (path == null) {
            return HookResult.proceed(event);
        }

        int count = readCounts.merge(path, 1, Integer::sum);

        if (count >= threshold && warnedFiles.add(path)) {
            String fileName = extractFileName(path);
            String message = String.format(
                    "You have read %s %d times. You already have this file's content in your context. "
                            + "Avoid re-reading files you have already seen — use your existing knowledge instead.",
                    fileName, count);
            return HookResult.inject(
                    event, Msg.of(MsgRole.USER, message), "StaleReadDetectorHook");
        }

        return HookResult.proceed(event);
    }

    /** Extract file path from tool result metadata, falling back to content first line. */
    static String extractPath(ToolResultEvent event) {
        // Priority 1: metadata "path" key
        Map<String, Object> metadata = event.result().metadata();
        if (metadata != null && metadata.containsKey("path")) {
            Object pathVal = metadata.get("path");
            if (pathVal instanceof String s && !s.isBlank()) {
                return s;
            }
        }

        // Priority 2: first line of content (e.g., "// path/to/file.java")
        String content = event.result().content();
        if (content != null && !content.isBlank()) {
            String firstLine = content.lines().findFirst().orElse("").trim();
            if (!firstLine.isBlank()) {
                return firstLine;
            }
        }

        return null;
    }

    /** Extract just the file name from a path for a cleaner warning message. */
    static String extractFileName(String path) {
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }
}
