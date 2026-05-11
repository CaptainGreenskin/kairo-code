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
import io.kairo.api.hook.PreActingEvent;
import io.kairo.api.hook.PreCompleteEvent;
import io.kairo.api.hook.SessionEndEvent;
import io.kairo.api.message.Content;
import io.kairo.api.model.ModelResponse;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes per-phase JSONL events to {@code .kairo-trace/session-{timestamp}.jsonl}.
 *
 * <p>Enables the agent to read its own execution trace and identify redundant tool calls,
 * slow iterations, and optimization opportunities.
 *
 * <p>Phases traced:
 * <ul>
 *   <li>{@link HookPhase#PRE_ACTING} — captures start timestamp for duration</li>
 *   <li>{@link HookPhase#POST_ACTING} — tool_name, duration_ms, result_status</li>
 *   <li>{@link HookPhase#POST_REASONING} — tokens_used, has_tool_calls, thinking_chars</li>
 *   <li>{@link HookPhase#PRE_COMPLETE} — final_text_chars</li>
 *   <li>{@link HookPhase#SESSION_END} — final_state, total_iterations, total_tokens, duration</li>
 * </ul>
 *
 * <p>Null workingDir means no-op (REPL-safe).
 */
public final class ExecutionTraceHook {

    private static final Logger log = LoggerFactory.getLogger(ExecutionTraceHook.class);

    private final Path workingDir;
    private final Path traceFile;
    private BufferedWriter writer;
    private boolean initialized;

    /** toolName -> epoch millis */
    private final Map<String, Long> toolStartTimes = new LinkedHashMap<>();

    /** Iteration counter, incremented on each POST_REASONING. */
    private int iteration;

    public ExecutionTraceHook(Path workingDir) {
        this.workingDir = workingDir;
        this.traceFile = workingDir != null
                ? workingDir
                .resolve(".kairo-trace")
                .resolve("session-" + System.currentTimeMillis() + ".jsonl")
                : null;
    }

    @HookHandler(HookPhase.PRE_ACTING)
    public HookResult<PreActingEvent> onPreActing(PreActingEvent event) {
        if (workingDir == null) {
            return HookResult.proceed(event);
        }
        toolStartTimes.put(event.toolName(), System.currentTimeMillis());
        return HookResult.proceed(event);
    }

    @HookHandler(HookPhase.POST_ACTING)
    public HookResult<PostActingEvent> onPostActing(PostActingEvent event) {
        if (workingDir == null) {
            return HookResult.proceed(event);
        }

        Long start = toolStartTimes.remove(event.toolName());
        long durationMs = start != null ? System.currentTimeMillis() - start : -1;

        String status;
        if (event.result().isError()) {
            status = "error";
        } else if (isTruncated(event)) {
            status = "truncated";
        } else {
            status = "success";
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("phase", "POST_ACTING");
        fields.put("iteration", iteration);
        fields.put("tool", event.toolName());
        fields.put("duration_ms", durationMs);
        fields.put("status", status);
        fields.put("ts", Instant.now().toString());

        writeLine(fields);
        return HookResult.proceed(event);
    }

    @HookHandler(HookPhase.POST_REASONING)
    public HookResult<PostReasoningEvent> onPostReasoning(PostReasoningEvent event) {
        if (workingDir == null) {
            return HookResult.proceed(event);
        }

        iteration++;
        ModelResponse response = event.response();

        // tokens field preserved as outputTokens for backward compat with existing trace readers;
        // additional fields below expose input / cache usage so "tokens: 0" in this row no longer
        // looks like missing-usage data (see fix-dual-tokenbudget-compaction-never-triggers).
        long tokens = 0;
        long inputTokens = 0;
        long cacheReadTokens = 0;
        long cacheWriteTokens = 0;
        boolean hasUsage = false;
        boolean hasToolCalls = false;
        int thinkingChars = 0;

        if (response != null) {
            if (response.usage() != null) {
                tokens = response.usage().outputTokens();
                inputTokens = response.usage().inputTokens();
                cacheReadTokens = response.usage().cacheReadTokens();
                cacheWriteTokens = response.usage().cacheCreationTokens();
                hasUsage = true;
            }
            if (response.contents() != null) {
                for (Content c : response.contents()) {
                    if (c instanceof Content.ToolUseContent) {
                        hasToolCalls = true;
                    } else if (c instanceof Content.ThinkingContent tc) {
                        thinkingChars += tc.thinking() != null ? tc.thinking().length() : 0;
                    }
                }
            }
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("phase", "POST_REASONING");
        fields.put("iteration", iteration);
        fields.put("tokens", tokens);
        fields.put("input_tokens", inputTokens);
        fields.put("cache_read_tokens", cacheReadTokens);
        fields.put("cache_write_tokens", cacheWriteTokens);
        fields.put("has_usage", hasUsage);
        fields.put("has_tool_calls", hasToolCalls);
        fields.put("thinking_chars", thinkingChars);
        fields.put("ts", Instant.now().toString());

        writeLine(fields);
        return HookResult.proceed(event);
    }

    @HookHandler(HookPhase.PRE_COMPLETE)
    public HookResult<PreCompleteEvent> onPreComplete(PreCompleteEvent event) {
        if (workingDir == null) {
            return HookResult.proceed(event);
        }

        int chars = 0;
        if (event.assistantMsg() != null && event.assistantMsg().text() != null) {
            chars = event.assistantMsg().text().length();
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("phase", "PRE_COMPLETE");
        fields.put("iteration", iteration);
        fields.put("final_text_chars", chars);
        fields.put("ts", Instant.now().toString());

        writeLine(fields);
        return HookResult.proceed(event);
    }

    @HookHandler(HookPhase.SESSION_END)
    public HookResult<SessionEndEvent> onSessionEnd(SessionEndEvent event) {
        if (workingDir == null) {
            return HookResult.proceed(event);
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("phase", "SESSION_END");
        fields.put("final_state", event.finalState() != null ? event.finalState().name() : "unknown");
        fields.put("total_iterations", event.iterations());
        fields.put("total_tokens", event.tokensUsed());
        fields.put("duration_seconds", event.duration() != null ? event.duration().toSeconds() : 0);
        fields.put("ts", Instant.now().toString());

        writeLine(fields);
        closeWriter();
        return HookResult.proceed(event);
    }

    private void writeLine(Map<String, Object> fields) {
        try {
            ensureWriter();
            if (writer == null) return;
            writer.write(toJson(fields));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.debug("ExecutionTraceHook write failed: {}", e.getMessage());
        }
    }

    private synchronized void ensureWriter() throws IOException {
        if (initialized || traceFile == null) return;
        Path parent = traceFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        writer = Files.newBufferedWriter(traceFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        initialized = true;
    }

    private void closeWriter() {
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException e) {
            log.debug("ExecutionTraceHook close failed: {}", e.getMessage());
        }
    }

    private static boolean isTruncated(PostActingEvent event) {
        String content = event.result().content();
        return content != null && content.endsWith("... (truncated)");
    }

    /** Minimal JSON serialization without external dependencies. */
    private static String toJson(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":");
            sb.append(valueToJson(e.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String valueToJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + escape(s) + "\"";
        if (value instanceof Boolean b) return b.toString();
        if (value instanceof Number n) return n.toString();
        return "\"" + escape(value.toString()) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
