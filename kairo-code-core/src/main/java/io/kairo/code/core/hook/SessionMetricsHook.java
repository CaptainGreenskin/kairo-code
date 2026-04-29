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
import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.message.Content;
import io.kairo.api.model.ModelResponse;

/**
 * Collects session efficiency metrics via hook phases.
 *
 * <p>Phase: {@link HookPhase#POST_ACTING} — records tool call counts and file reads.
 * Phase: {@link HookPhase#POST_REASONING} — records iterations without tool calls.
 */
public final class SessionMetricsHook {

    private final SessionMetricsCollector metrics;

    public SessionMetricsHook(SessionMetricsCollector metrics) {
        this.metrics = metrics;
    }

    @HookHandler(HookPhase.POST_ACTING)
    public HookResult<PostActingEvent> onPostActing(PostActingEvent event) {
        metrics.recordToolCall(event.toolName());

        // Detect file reads: if tool name contains "read" and result metadata has a path
        if (event.toolName().toLowerCase().contains("read")) {
            String path = extractPath(event);
            if (path != null) {
                metrics.recordFileRead(path);
            }
        }

        return HookResult.proceed(event);
    }

    @HookHandler(HookPhase.POST_REASONING)
    public HookResult<PostReasoningEvent> onPostReasoning(PostReasoningEvent event) {
        if (event.response() == null || event.response().contents() == null) {
            metrics.recordIterationWithoutTools();
            return HookResult.proceed(event);
        }

        boolean hasToolCall = false;
        for (Content content : event.response().contents()) {
            if (content instanceof Content.ToolUseContent) {
                hasToolCall = true;
                break;
            }
        }
        if (!hasToolCall) {
            metrics.recordIterationWithoutTools();
        }

        return HookResult.proceed(event);
    }

    private static String extractPath(PostActingEvent event) {
        var metadata = event.result().metadata();
        if (metadata != null && metadata.containsKey("path")) {
            Object pathVal = metadata.get("path");
            if (pathVal instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        // Fallback: first line of content
        String content = event.result().content();
        if (content != null && !content.isBlank()) {
            String firstLine = content.lines().findFirst().orElse("").trim();
            if (!firstLine.isBlank()) {
                return firstLine;
            }
        }
        return null;
    }
}
