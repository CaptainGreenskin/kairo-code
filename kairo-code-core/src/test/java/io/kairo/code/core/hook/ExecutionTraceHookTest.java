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

import io.kairo.api.agent.AgentState;
import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.PostActingEvent;
import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.hook.PreActingEvent;
import io.kairo.api.hook.PreCompleteEvent;
import io.kairo.api.hook.SessionEndEvent;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelResponse;
import io.kairo.api.model.ModelResponse.Usage;
import io.kairo.api.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionTraceHookTest {

    @Test
    void nullWorkingDir_noOp_doesNotThrow() {
        ExecutionTraceHook hook = new ExecutionTraceHook(null);

        HookResult<PreActingEvent> r1 = hook.onPreActing(preActing("bash_execute"));
        HookResult<PostActingEvent> r2 = hook.onPostActing(postActing("bash_execute", false));
        HookResult<PostReasoningEvent> r3 = hook.onPostReasoning(postReasoning());
        HookResult<PreCompleteEvent> r4 = hook.onPreComplete(preComplete());
        HookResult<SessionEndEvent> r5 = hook.onSessionEnd(sessionEnd());

        assertThat(r1.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r3.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r4.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r5.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void postActing_writesCorrectJsonFields(@TempDir Path tempDir) throws Exception {
        ExecutionTraceHook hook = new ExecutionTraceHook(tempDir);

        hook.onPreActing(preActing("bash_execute"));
        Thread.sleep(10); // ensure duration_ms > 0
        HookResult<PostActingEvent> result = hook.onPostActing(postActing("bash_execute", false));

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);

        Path traceFile = findTraceFile(tempDir);
        assertThat(traceFile).exists();

        String content = Files.readString(traceFile);
        assertThat(content).contains("\"phase\":\"POST_ACTING\"");
        assertThat(content).contains("\"tool\":\"bash_execute\"");
        assertThat(content).contains("\"status\":\"success\"");
        assertThat(content).contains("\"duration_ms\":");
        assertThat(content).contains("\"ts\":");
    }

    @Test
    void postActing_errorStatus(@TempDir Path tempDir) throws Exception {
        ExecutionTraceHook hook = new ExecutionTraceHook(tempDir);

        hook.onPreActing(preActing("bash_execute"));
        hook.onPostActing(postActing("bash_execute", true));

        String content = Files.readString(findTraceFile(tempDir));
        assertThat(content).contains("\"status\":\"error\"");
    }

    @Test
    void sessionEnd_writesFinalState(@TempDir Path tempDir) throws Exception {
        ExecutionTraceHook hook = new ExecutionTraceHook(tempDir);

        hook.onSessionEnd(sessionEnd());

        String content = Files.readString(findTraceFile(tempDir));
        assertThat(content).contains("\"phase\":\"SESSION_END\"");
        assertThat(content).contains("\"final_state\":\"COMPLETED\"");
        assertThat(content).contains("\"total_iterations\":12");
        assertThat(content).contains("\"total_tokens\":45200");
        assertThat(content).contains("\"duration_seconds\":187");
    }

    @Test
    void sessionEnd_failedState(@TempDir Path tempDir) throws Exception {
        ExecutionTraceHook hook = new ExecutionTraceHook(tempDir);

        hook.onSessionEnd(new SessionEndEvent(
                "kairo-code", AgentState.FAILED, 3, 1200, Duration.ofSeconds(30), "OOM"));

        String content = Files.readString(findTraceFile(tempDir));
        assertThat(content).contains("\"final_state\":\"FAILED\"");
    }

    @Test
    void multipleEvents_appendedToFile(@TempDir Path tempDir) throws Exception {
        ExecutionTraceHook hook = new ExecutionTraceHook(tempDir);

        hook.onPreActing(preActing("read_file"));
        hook.onPostActing(postActing("read_file", false));
        hook.onPostReasoning(postReasoning());
        hook.onPreActing(preActing("bash_execute"));
        hook.onPostActing(postActing("bash_execute", false));
        hook.onSessionEnd(sessionEnd());

        String content = Files.readString(findTraceFile(tempDir));
        // Each event is on its own line
        List<String> lines = content.lines().toList();
        assertThat(lines).hasSize(4); // 2 POST_ACTING + 1 POST_REASONING + 1 SESSION_END

        // Verify each line is valid JSON with a phase field
        for (String line : lines) {
            assertThat(line).startsWith("{").endsWith("}");
            assertThat(line).contains("\"phase\":");
        }
    }

    @Test
    void postReasoning_writesToolCallsAndTokens(@TempDir Path tempDir) throws Exception {
        ExecutionTraceHook hook = new ExecutionTraceHook(tempDir);

        // Response with tool use and thinking
        ModelResponse response = new ModelResponse(
                "resp-1",
                List.of(
                        new Content.ThinkingContent("Let me think about this...", 1000, "sig-1"),
                        new Content.ToolUseContent("tool-1", "bash_execute", Map.of("command", "ls"))
                ),
                new Usage(100, 200, 0, 0),
                ModelResponse.StopReason.TOOL_USE,
                "gpt-4o"
        );
        hook.onPostReasoning(new PostReasoningEvent(response, false));

        String content = Files.readString(findTraceFile(tempDir));
        assertThat(content).contains("\"phase\":\"POST_REASONING\"");
        assertThat(content).contains("\"iteration\":1");
        assertThat(content).contains("\"tokens\":200");
        assertThat(content).contains("\"has_tool_calls\":true");
        assertThat(content).contains("\"thinking_chars\":");
    }

    @Test
    void postReasoning_noToolCalls(@TempDir Path tempDir) throws Exception {
        ExecutionTraceHook hook = new ExecutionTraceHook(tempDir);

        ModelResponse response = new ModelResponse(
                "resp-1",
                List.of(new Content.TextContent("I'm done with the task.")),
                new Usage(100, 50, 0, 0),
                ModelResponse.StopReason.END_TURN,
                "gpt-4o"
        );
        hook.onPostReasoning(new PostReasoningEvent(response, false));

        String content = Files.readString(findTraceFile(tempDir));
        assertThat(content).contains("\"has_tool_calls\":false");
        assertThat(content).contains("\"tokens\":50");
    }

    @Test
    void preComplete_writesFinalTextChars(@TempDir Path tempDir) throws Exception {
        ExecutionTraceHook hook = new ExecutionTraceHook(tempDir);

        hook.onPreComplete(preComplete());

        String content = Files.readString(findTraceFile(tempDir));
        assertThat(content).contains("\"phase\":\"PRE_COMPLETE\"");
        assertThat(content).contains("\"final_text_chars\":");
    }

    @Test
    void ioException_doesNotThrow(@TempDir Path tempDir) throws Exception {
        // Write to a read-only directory to trigger IO errors
        Path readOnlyDir = tempDir.resolve("readonly");
        Files.createDirectories(readOnlyDir);
        // Make it non-writable
        readOnlyDir.toFile().setWritable(false);

        try {
            ExecutionTraceHook hook = new ExecutionTraceHook(readOnlyDir);

            // Should not throw — IO exceptions are swallowed
            hook.onPostReasoning(postReasoning());
            hook.onSessionEnd(sessionEnd());
        } finally {
            // Restore write permission so temp dir can be cleaned up
            readOnlyDir.toFile().setWritable(true);
        }
    }

    @Test
    void createsKairoTraceDirectory(@TempDir Path tempDir) throws Exception {
        Path subDir = tempDir.resolve("project");
        Files.createDirectories(subDir);

        ExecutionTraceHook hook = new ExecutionTraceHook(subDir);
        hook.onSessionEnd(sessionEnd());

        Path traceDir = subDir.resolve(".kairo-trace");
        assertThat(traceDir).isDirectory();
        assertThat(findTraceFile(subDir)).exists();
    }

    // --- helpers ---

    private static PreActingEvent preActing(String toolName) {
        return new PreActingEvent(toolName, Map.of(), false);
    }

    private static PostActingEvent postActing(String toolName, boolean isError) {
        return new PostActingEvent(
                toolName, isError ? ToolResult.error("tool-1", "ok") : ToolResult.success("tool-1", "ok"));
    }

    private static PostReasoningEvent postReasoning() {
        ModelResponse response = new ModelResponse(
                "resp-1",
                List.of(new Content.TextContent("thinking...")),
                new Usage(100, 200, 0, 0),
                ModelResponse.StopReason.TOOL_USE,
                "gpt-4o"
        );
        return new PostReasoningEvent(response, false);
    }

    private static PreCompleteEvent preComplete() {
        return new PreCompleteEvent(
                Msg.of(MsgRole.ASSISTANT, "Here is the final answer text."),
                List.of(),
                false
        );
    }

    private static SessionEndEvent sessionEnd() {
        return new SessionEndEvent(
                "kairo-code", AgentState.COMPLETED, 12, 45200, Duration.ofSeconds(187), null);
    }

    private static Path findTraceFile(Path dir) throws Exception {
        try (var stream = Files.walk(dir.resolve(".kairo-trace"))) {
            return stream
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No .jsonl file found"));
        }
    }
}
