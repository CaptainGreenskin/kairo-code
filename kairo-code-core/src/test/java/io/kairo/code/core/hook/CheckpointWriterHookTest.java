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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.hook.ToolResultEvent;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelResponse;
import io.kairo.api.model.ModelResponse.Usage;
import io.kairo.api.tool.ToolOutcome;
import io.kairo.api.tool.ToolOutput;
import io.kairo.api.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckpointWriterHookTest {

    @Test
    void nullWorkingDir_noOp_doesNotThrow() {
        Msg userMsg = Msg.of(MsgRole.USER, "Fix the bug");
        CheckpointWriterHook hook = new CheckpointWriterHook(null, new ObjectMapper(), userMsg);

        HookResult<PostReasoningEvent> r = hook.onPostReasoning(postReasoning("Done."));
        assertThat(r.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void writesCheckpoint_afterPostReasoning(@TempDir Path tempDir) throws Exception {
        Msg userMsg = Msg.of(MsgRole.USER, "Fix the bug");
        CheckpointWriterHook hook = new CheckpointWriterHook(tempDir, new ObjectMapper(), userMsg);

        hook.onPostReasoning(postReasoning("I'll fix it."));

        Path checkpointFile = tempDir.resolve(".kairo-session/checkpoint.json");
        assertThat(checkpointFile).exists();

        String content = Files.readString(checkpointFile);
        assertThat(content).contains("\"sessionId\"");
        assertThat(content).contains("\"iteration\"");
        assertThat(content).contains("\"timestamp\"");
        assertThat(content).contains("\"messages\"");
        assertThat(content).contains("\"role\"");
        assertThat(content).contains("user");
        assertThat(content).contains("Fix the bug");
    }

    @Test
    void secondWrite_overridesFirst(@TempDir Path tempDir) throws Exception {
        Msg userMsg = Msg.of(MsgRole.USER, "Fix the bug");
        CheckpointWriterHook hook = new CheckpointWriterHook(tempDir, new ObjectMapper(), userMsg);

        hook.onPostReasoning(postReasoning("First response"));
        Path checkpointFile = tempDir.resolve(".kairo-session/checkpoint.json");
        long firstSize = Files.size(checkpointFile);

        hook.onPostReasoning(postReasoning("Second response with more content"));
        long secondSize = Files.size(checkpointFile);

        assertThat(secondSize).isNotEqualTo(firstSize);

        String content = Files.readString(checkpointFile);
        // Should contain the second response, not the first
        assertThat(content).contains("Second response with more content");
        assertThat(content).contains("Fix the bug");
    }

    @Test
    void includesAssistantMessage(@TempDir Path tempDir) throws Exception {
        Msg userMsg = Msg.of(MsgRole.USER, "Fix the bug");
        CheckpointWriterHook hook = new CheckpointWriterHook(tempDir, new ObjectMapper(), userMsg);

        ModelResponse response = new ModelResponse(
                "resp-1",
                List.of(
                        new Content.TextContent("Let me fix it"),
                        new Content.ToolUseContent("tool-1", "edit", Map.of("path", "Foo.java"))
                ),
                new Usage(100, 200, 0, 0),
                ModelResponse.StopReason.TOOL_USE,
                "gpt-4o"
        );
        hook.onPostReasoning(new PostReasoningEvent(response, false));

        String content = Files.readString(
                tempDir.resolve(".kairo-session/checkpoint.json"));
        assertThat(content).contains("Let me fix it");
        assertThat(content).contains("Fix the bug");
        // The tool_use must round-trip into the checkpoint, not just the text preamble.
        assertThat(content).contains("\"toolCalls\"");
        assertThat(content).contains("\"id\" : \"tool-1\"");
        assertThat(content).contains("\"name\" : \"edit\"");
        assertThat(content).contains("Foo.java");
    }

    @Test
    void toolUseOnlyResponse_preservedInCheckpoint(@TempDir Path tempDir) throws Exception {
        // Regression: when the model emits ONLY a tool_use (no accompanying text/thinking),
        // the assistant message used to be dropped because the old code only built a Msg
        // from text/thinking content. Without this message, the next iteration would have
        // no record of the tool call, breaking :resume and evolution.
        Msg userMsg = Msg.of(MsgRole.USER, "Read foo.txt");
        CheckpointWriterHook hook = new CheckpointWriterHook(tempDir, new ObjectMapper(), userMsg);

        ModelResponse response = new ModelResponse(
                "resp-1",
                List.of(new Content.ToolUseContent("tool-42", "read", Map.of("path", "foo.txt"))),
                new Usage(50, 60, 0, 0),
                ModelResponse.StopReason.TOOL_USE,
                "gpt-4o"
        );
        hook.onPostReasoning(new PostReasoningEvent(response, false));

        String content = Files.readString(tempDir.resolve(".kairo-session/checkpoint.json"));
        assertThat(content).contains("\"toolCalls\"");
        assertThat(content).contains("\"id\" : \"tool-42\"");
        assertThat(content).contains("\"name\" : \"read\"");
    }

    @Test
    void thinkingOnlyResponse_preservedInCheckpoint(@TempDir Path tempDir) throws Exception {
        // Regression for MiniMax-M2: emitted only <think> content (ThinkingContent),
        // no TextContent. Previously the StringBuilder pathway captured this, but the
        // new code path must keep parity — verify thinking still survives.
        Msg userMsg = Msg.of(MsgRole.USER, "Plan it");
        CheckpointWriterHook hook = new CheckpointWriterHook(tempDir, new ObjectMapper(), userMsg);

        ModelResponse response = new ModelResponse(
                "resp-1",
                List.of(new Content.ThinkingContent("step 1: read the file", 0, "")),
                new Usage(10, 20, 0, 0),
                ModelResponse.StopReason.END_TURN,
                "gpt-4o"
        );
        hook.onPostReasoning(new PostReasoningEvent(response, false));

        String content = Files.readString(tempDir.resolve(".kairo-session/checkpoint.json"));
        assertThat(content).contains("step 1: read the file");
    }

    @Test
    void toolResultHandler_appendsToolMsg(@TempDir Path tempDir) throws Exception {
        // The new TOOL_RESULT handler must persist tool results so the checkpoint
        // contains a TOOL-role msg with toolCallId — without this, :resume can replay
        // tool_use calls but the model has no record of what they returned.
        Msg userMsg = Msg.of(MsgRole.USER, "Read foo.txt");
        CheckpointWriterHook hook = new CheckpointWriterHook(tempDir, new ObjectMapper(), userMsg);

        // First the model issues a tool_use
        ModelResponse callResp = new ModelResponse(
                "resp-1",
                List.of(new Content.ToolUseContent("call-1", "read", Map.of("path", "foo.txt"))),
                new Usage(10, 20, 0, 0),
                ModelResponse.StopReason.TOOL_USE,
                "gpt-4o"
        );
        hook.onPostReasoning(new PostReasoningEvent(callResp, false));

        // Then the tool result fires
        ToolResult result = new ToolResult(
                "call-1",
                new ToolOutput.Text("file contents: hello"),
                ToolOutcome.SUCCESS,
                List.of(),
                Map.of());
        hook.onToolResult(new ToolResultEvent("read", result, Duration.ofMillis(12), true));

        String content = Files.readString(tempDir.resolve(".kairo-session/checkpoint.json"));
        assertThat(content).contains("\"tool\""); // TOOL role lowercased
        assertThat(content).contains("\"toolCallId\" : \"call-1\"");
        assertThat(content).contains("file contents: hello");
    }

    @Test
    void streamingResult_inputMapIsRedacted(@TempDir Path tempDir) throws Exception {
        // M-B4 regression: streaming-path tool execution embeds the raw tool result inside
        // ToolUseContent.input under _streaming_result, bypassing the agent's GuardrailChain.
        // Without explicit redaction at checkpoint serialization, secrets in tool output land
        // in checkpoint.json verbatim. Verify PII patterns are applied.
        Msg userMsg = Msg.of(MsgRole.USER, "Read /tmp/secrets.txt");
        CheckpointWriterHook hook = new CheckpointWriterHook(tempDir, new ObjectMapper(), userMsg);

        ModelResponse response = new ModelResponse(
                "streaming",
                List.of(
                        new Content.ToolUseContent(
                                "call-1",
                                "read",
                                Map.of(
                                        "file_path", "/tmp/secrets.txt",
                                        "_streaming_result",
                                        "email=alice@example.com api=sk-abcdef1234567890abcdef"))),
                new Usage(50, 60, 0, 0),
                ModelResponse.StopReason.TOOL_USE,
                "MiniMax-M2"
        );
        hook.onPostReasoning(new PostReasoningEvent(response, false));

        String content = Files.readString(tempDir.resolve(".kairo-session/checkpoint.json"));
        // The raw values must be gone — replaced by redaction markers.
        assertThat(content).doesNotContain("alice@example.com");
        assertThat(content).doesNotContain("sk-abcdef1234567890abcdef");
        assertThat(content).contains("<redacted:email>");
        assertThat(content).contains("<redacted:api-key>");
        // file_path is not a PII match, should survive.
        assertThat(content).contains("/tmp/secrets.txt");
    }

    @Test
    void toolResultHandler_errorOutcome_persistsAsError(@TempDir Path tempDir) throws Exception {
        Msg userMsg = Msg.of(MsgRole.USER, "Run failing tool");
        CheckpointWriterHook hook = new CheckpointWriterHook(tempDir, new ObjectMapper(), userMsg);

        ToolResult result = new ToolResult(
                "call-99",
                new ToolOutput.Text("permission denied"),
                ToolOutcome.ERROR,
                List.of(),
                Map.of());
        hook.onToolResult(new ToolResultEvent("bash", result, Duration.ofMillis(5), false));

        String content = Files.readString(tempDir.resolve(".kairo-session/checkpoint.json"));
        assertThat(content).contains("permission denied");
        assertThat(content).contains("\"toolCallId\" : \"call-99\"");
    }

    @Test
    void ioException_doesNotThrow(@TempDir Path tempDir) throws Exception {
        // Write to a read-only directory to trigger IO errors
        Path readOnlyDir = tempDir.resolve("readonly");
        Files.createDirectories(readOnlyDir);
        readOnlyDir.toFile().setWritable(false);

        try {
            Msg userMsg = Msg.of(MsgRole.USER, "Fix the bug");
            CheckpointWriterHook hook = new CheckpointWriterHook(readOnlyDir, new ObjectMapper(), userMsg);

            hook.onPostReasoning(postReasoning("Response"));
        } finally {
            readOnlyDir.toFile().setWritable(true);
        }
    }

    @Test
    void nullInitialMessage_noCrash(@TempDir Path tempDir) throws Exception {
        CheckpointWriterHook hook = new CheckpointWriterHook(tempDir, new ObjectMapper(), null);

        hook.onPostReasoning(postReasoning("Response"));

        Path checkpointFile = tempDir.resolve(".kairo-session/checkpoint.json");
        assertThat(checkpointFile).exists();

        String content = Files.readString(checkpointFile);
        assertThat(content).contains("Response");
    }

    @Test
    void createsKairoSessionDirectory(@TempDir Path tempDir) throws Exception {
        Path subDir = tempDir.resolve("project");
        Files.createDirectories(subDir);

        Msg userMsg = Msg.of(MsgRole.USER, "Fix the bug");
        CheckpointWriterHook hook = new CheckpointWriterHook(subDir, new ObjectMapper(), userMsg);
        hook.onPostReasoning(postReasoning("Done."));

        Path sessionDir = subDir.resolve(".kairo-session");
        assertThat(sessionDir).isDirectory();
    }

    // --- helpers ---

    private static PostReasoningEvent postReasoning(String text) {
        ModelResponse response = new ModelResponse(
                "resp-1",
                List.of(new Content.TextContent(text)),
                new Usage(100, 200, 0, 0),
                ModelResponse.StopReason.END_TURN,
                "gpt-4o"
        );
        return new PostReasoningEvent(response, false);
    }
}
