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
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelResponse;
import io.kairo.api.model.ModelResponse.Usage;
import java.nio.file.Files;
import java.nio.file.Path;
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
