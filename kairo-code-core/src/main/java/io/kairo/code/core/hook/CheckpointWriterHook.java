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
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelResponse;
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
        this.workingDir = workingDir;
        this.objectMapper = objectMapper != null
                ? objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT)
                : new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.checkpointPath = workingDir != null
                ? workingDir.resolve(CHECKPOINT_DIR).resolve(CHECKPOINT_FILE)
                : null;
        this.sessionId = "session-" + System.currentTimeMillis();
        this.initialUserMessage = initialUserMessage;
        this.messages = new ArrayList<>();
        if (initialUserMessage != null) {
            messages.add(initialUserMessage);
        }
    }

    @HookHandler(HookPhase.POST_REASONING)
    public HookResult<PostReasoningEvent> onPostReasoning(PostReasoningEvent event) {
        if (workingDir == null) {
            return HookResult.proceed(event);
        }

        iteration++;
        ModelResponse response = event.response();

        // Convert the model response to an assistant Msg and accumulate it.
        if (response != null && response.contents() != null && !response.contents().isEmpty()) {
            // Build a text representation of the response for the checkpoint.
            StringBuilder text = new StringBuilder();
            for (Content c : response.contents()) {
                if (c instanceof Content.TextContent tc) {
                    text.append(tc.text());
                } else if (c instanceof Content.ThinkingContent tc) {
                    text.append(tc.thinking());
                }
            }
            if (text.length() > 0) {
                Msg assistantMsg = Msg.of(MsgRole.ASSISTANT, text.toString());
                messages.add(assistantMsg);
            }
        }

        writeCheckpoint();
        return HookResult.proceed(event);
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
                sb.append(tc.thinking());
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
                call.put("input", tc.input());
                toolCalls.add(call);
            }
        }
        return toolCalls;
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
