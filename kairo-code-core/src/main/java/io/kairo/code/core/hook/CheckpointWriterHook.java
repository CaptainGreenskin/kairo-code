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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.kairo.api.hook.HookHandler;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.hook.ToolResultEvent;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelResponse;
import io.kairo.api.tool.ToolOutcome;
import io.kairo.api.tool.ToolOutput;
import io.kairo.api.tool.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a session checkpoint to {@code .kairo-session/checkpoint.json} after each reasoning step.
 *
 * <p>Enables interrupted long-running tasks (e.g. Defects4J) to resume from the last checkpoint
 * instead of starting over.
 *
 * <p>The checkpoint is overwritten on each {@link HookPhase#POST_REASONING} so only the latest
 * state is kept. Writes are atomic: a temp file is written first, then moved into place.
 *
 * <p>Null workingDir means no-op.
 */
public final class CheckpointWriterHook {

    private static final Logger log = LoggerFactory.getLogger(CheckpointWriterHook.class);

    private static final String CHECKPOINT_DIR = ".kairo-session";
    private static final String CHECKPOINT_FILE = "checkpoint.json";
    private static final String TMP_SUFFIX = ".tmp";

    private final Path workingDir;
    private final Path checkpointPath;
    private final ObjectMapper objectMapper;
    private final String sessionId;

    /** The initial user message — set once when the hook is constructed. */
    private final Msg initialUserMessage;

    /** Accumulated conversation: starts with the initial user message. */
    private final List<Msg> messages;

    /** Monotonic iteration counter, incremented on each POST_REASONING. */
    private int iteration;

    /**
     * @param workingDir project working directory; null disables checkpointing.
     * @param objectMapper Jackson mapper for serializing messages.
     * @param initialUserMessage the first user message that started the session.
     */
    public CheckpointWriterHook(Path workingDir, ObjectMapper objectMapper, Msg initialUserMessage) {
        this(workingDir, null, objectMapper, initialUserMessage);
    }

    /**
     * @param workingDir project working directory; null disables checkpointing.
     * @param sessionId explicit session ID for per-session isolation; null falls back to timestamp.
     * @param objectMapper Jackson mapper for serializing messages.
     * @param initialUserMessage the first user message that started the session.
     */
    public CheckpointWriterHook(
            Path workingDir, String sessionId, ObjectMapper objectMapper, Msg initialUserMessage) {
        this.workingDir = workingDir;
        this.objectMapper = objectMapper != null
                ? objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT)
                : new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.sessionId = sessionId != null ? sessionId : "session-" + System.currentTimeMillis();
        this.checkpointPath = workingDir != null
                ? workingDir.resolve(CHECKPOINT_DIR).resolve(this.sessionId).resolve(CHECKPOINT_FILE)
                : null;
        this.initialUserMessage = initialUserMessage;
        this.messages = new ArrayList<>();
        if (initialUserMessage != null) {
            messages.add(initialUserMessage);
        }
    }

    /**
     * Record a user message into the checkpoint. Called by web session payloads
     * before {@code agent.call()} so the user turn is captured in the checkpoint.
     */
    public void recordUserMessage(Msg userMsg) {
        if (workingDir == null || userMsg == null) return;
        messages.add(userMsg);
        writeCheckpoint();
    }

    @HookHandler(HookPhase.POST_REASONING)
    public HookResult<PostReasoningEvent> onPostReasoning(PostReasoningEvent event) {
        if (workingDir == null) {
            return HookResult.proceed(event);
        }

        iteration++;
        ModelResponse response = event.response();

        // Preserve the assistant message verbatim — text, thinking AND tool_use content all need
        // to survive into the checkpoint so :resume can restore the full tool history and so
        // evolution / snapshot consumers see what tools the model invoked. Previously only
        // text/thinking were extracted, dropping every ToolUseContent on the floor.
        if (response != null && response.contents() != null && !response.contents().isEmpty()) {
            Msg.Builder builder = Msg.builder().role(MsgRole.ASSISTANT);
            for (Content c : response.contents()) {
                builder.addContent(c);
            }
            Msg assistantMsg = builder.build();
            if (!assistantMsg.contents().isEmpty()) {
                messages.add(assistantMsg);
            }
        }

        writeCheckpoint();
        return HookResult.proceed(event);
    }

    @HookHandler(HookPhase.TOOL_RESULT)
    public HookResult<ToolResultEvent> onToolResult(ToolResultEvent event) {
        if (workingDir == null || event == null) {
            return HookResult.proceed(event);
        }
        ToolResult result = event.result();
        if (result == null) {
            return HookResult.proceed(event);
        }
        String text = renderOutput(result.output());
        boolean isError = result.outcome() != ToolOutcome.SUCCESS;
        Msg toolMsg = Msg.builder()
                .role(MsgRole.TOOL)
                .addContent(new Content.ToolResultContent(result.toolUseId(), text, isError))
                .build();
        messages.add(toolMsg);
        writeCheckpoint();
        return HookResult.proceed(event);
    }

    private static String renderOutput(ToolOutput output) {
        if (output == null) return "";
        if (output instanceof ToolOutput.Text t) return t.content();
        if (output instanceof ToolOutput.Truncated t) return t.visible();
        if (output instanceof ToolOutput.Structured s) return String.valueOf(s.data());
        if (output instanceof ToolOutput.Binary b) return "<binary " + b.mime() + ">";
        return output.toString();
    }

    private void writeCheckpoint() {
        if (checkpointPath == null || messages.isEmpty()) {
            return;
        }

        try {
            Path parent = checkpointPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Map<String, Object> checkpoint = new LinkedHashMap<>();
            checkpoint.put("sessionId", sessionId);
            checkpoint.put("iteration", iteration);
            checkpoint.put("timestamp", Instant.now().toString());
            checkpoint.put("messages", serializeMessages(messages));

            Path tmpPath = checkpointPath.resolveSibling(CHECKPOINT_FILE + TMP_SUFFIX);
            objectMapper.writeValue(tmpPath.toFile(), checkpoint);
            Files.move(tmpPath, checkpointPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.debug("CheckpointWriterHook write failed: {}", e.getMessage());
        }
    }

    /**
     * Serialize messages into a list of maps that Jackson can write.
     */
    private List<Map<String, Object>> serializeMessages(List<Msg> msgs) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Msg msg : msgs) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", msg.role().name().toLowerCase());
            entry.put("content", extractContent(msg));
            if (msg.role() == MsgRole.ASSISTANT) {
                List<Map<String, Object>> toolCalls = extractToolCalls(msg);
                if (!toolCalls.isEmpty()) {
                    entry.put("toolCalls", toolCalls);
                }
            }
            if (msg.role() == MsgRole.TOOL) {
                entry.put("toolCallId", extractToolCallId(msg));
            }
            result.add(entry);
        }
        return result;
    }

    private String extractContent(Msg msg) {
        if (msg.contents() == null || msg.contents().isEmpty()) {
            return msg.text() != null ? msg.text() : "";
        }
        StringBuilder sb = new StringBuilder();
        for (Content c : msg.contents()) {
            if (c instanceof Content.TextContent tc) {
                sb.append(tc.text());
            } else if (c instanceof Content.ThinkingContent tc) {
                sb.append("<think>").append(tc.thinking()).append("</think>");
            } else if (c instanceof Content.ToolResultContent tc) {
                sb.append(tc.content());
            }
        }
        return sb.toString();
    }

    private List<Map<String, Object>> extractToolCalls(Msg msg) {
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        if (msg.contents() == null) return toolCalls;
        for (Content c : msg.contents()) {
            if (c instanceof Content.ToolUseContent tc) {
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", tc.toolId());
                call.put("name", tc.toolName());
                call.put("input", redactToolInput(tc.input()));
                toolCalls.add(call);
            }
        }
        return toolCalls;
    }

    /**
     * M-B4: streaming-tool path stuffs the raw tool result into a ToolUseContent's input map
     * under the {@code _streaming_result} key (see ReasoningPhase.buildSyntheticStreamingResponse).
     * That bypasses the agent's GuardrailChain — the value reaches checkpoint persistence raw.
     * Apply the same default PII redaction here before serializing so emails / API keys / SSNs
     * inside streaming-result snapshots get the same {@code <redacted:*>} treatment as model
     * output text. No-op when {@code KAIRO_PII_REDACTION=off}.
     */
    private static Map<String, Object> redactToolInput(Map<String, Object> input) {
        if (input == null || input.isEmpty()) return input;
        if ("off".equalsIgnoreCase(System.getenv("KAIRO_PII_REDACTION"))) return input;
        var redacted = new LinkedHashMap<String, Object>(input);
        Object streamingResult = redacted.get("_streaming_result");
        if (streamingResult instanceof String s) {
            redacted.put("_streaming_result", redactString(s));
        }
        return redacted;
    }

    private static String redactString(String s) {
        if (s == null || s.isEmpty()) return s;
        String result = s;
        for (var pattern : io.kairo.security.pii.PiiPattern.values()) {
            result = pattern.pattern().matcher(result).replaceAll(pattern.replacement());
        }
        return result;
    }

    private String extractToolCallId(Msg msg) {
        if (msg.contents() == null || msg.contents().isEmpty()) return null;
        Content first = msg.contents().get(0);
        if (first instanceof Content.ToolResultContent tc) {
            return tc.toolUseId();
        }
        return null;
    }
}
