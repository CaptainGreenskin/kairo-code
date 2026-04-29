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

import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.message.Content;
import io.kairo.api.model.ModelResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NoWriteDetectedHookTest {

    private static PostReasoningEvent eventWithToolCalls(Content... toolCalls) {
        ModelResponse response =
                new ModelResponse(
                        "resp-1",
                        List.of(toolCalls),
                        null,
                        ModelResponse.StopReason.TOOL_USE,
                        "gpt-4o");
        return new PostReasoningEvent(response, false);
    }

    private static Content.ToolUseContent bash(String command) {
        return new Content.ToolUseContent("tool-1", "bash", Map.of("command", command));
    }

    private static Content.ToolUseContent readFile(String path) {
        return new Content.ToolUseContent("tool-1", "read_file", Map.of("path", path));
    }

    private static Content.ToolUseContent writeFile(String path) {
        return new Content.ToolUseContent(
                "tool-1", "write_file", Map.of("path", path, "content", "hello"));
    }

    @Test
    void bashOnly_triggersAfterThreshold() {
        NoWriteDetectedHook hook = new NoWriteDetectedHook(3, false);

        // First two turns: no injection yet
        HookResult<PostReasoningEvent> r1 = hook.onPostReasoning(eventWithToolCalls(bash("ls")));
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.CONTINUE);

        HookResult<PostReasoningEvent> r2 = hook.onPostReasoning(eventWithToolCalls(bash("cat Foo.java")));
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);

        // Third turn: threshold reached, inject
        HookResult<PostReasoningEvent> r3 = hook.onPostReasoning(eventWithToolCalls(bash("echo hello > Foo.java")));
        assertThat(r3.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(r3.injectedMessage()).isNotNull();
        assertThat(r3.injectedMessage().text()).contains("MUST call write_file or edit_file");
    }

    @Test
    void readFileOnly_triggersAfterThreshold() {
        NoWriteDetectedHook hook = new NoWriteDetectedHook(2, false);

        HookResult<PostReasoningEvent> r1 = hook.onPostReasoning(eventWithToolCalls(readFile("Foo.java")));
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.CONTINUE);

        HookResult<PostReasoningEvent> r2 = hook.onPostReasoning(eventWithToolCalls(readFile("Bar.java")));
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.INJECT);
    }

    @Test
    void writeFileCall_resetsCounter() {
        NoWriteDetectedHook hook = new NoWriteDetectedHook(3, false);

        // Two turns without write
        hook.onPostReasoning(eventWithToolCalls(bash("ls")));
        hook.onPostReasoning(eventWithToolCalls(readFile("Foo.java")));

        // Third turn: uses write_file, counter resets
        HookResult<PostReasoningEvent> r3 = hook.onPostReasoning(eventWithToolCalls(writeFile("Foo.java")));
        assertThat(r3.decision()).isEqualTo(HookResult.Decision.CONTINUE);

        // Now need 3 more turns to trigger again
        hook.onPostReasoning(eventWithToolCalls(bash("ls")));
        hook.onPostReasoning(eventWithToolCalls(bash("ls")));
        HookResult<PostReasoningEvent> r6 = hook.onPostReasoning(eventWithToolCalls(bash("ls")));
        assertThat(r6.decision()).isEqualTo(HookResult.Decision.INJECT);

        HookResult<PostReasoningEvent> r7 = hook.onPostReasoning(eventWithToolCalls(bash("ls")));
        assertThat(r7.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void secondInjection_suppressed() {
        NoWriteDetectedHook hook = new NoWriteDetectedHook(2, false);

        hook.onPostReasoning(eventWithToolCalls(bash("ls")));
        HookResult<PostReasoningEvent> r2 = hook.onPostReasoning(eventWithToolCalls(bash("ls")));
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.INJECT);

        // After injection, further calls should not inject again
        HookResult<PostReasoningEvent> r3 = hook.onPostReasoning(eventWithToolCalls(bash("ls")));
        assertThat(r3.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void replMode_doesNotTrigger() {
        NoWriteDetectedHook hook = new NoWriteDetectedHook(1, true);

        HookResult<PostReasoningEvent> r = hook.onPostReasoning(eventWithToolCalls(bash("ls")));
        assertThat(r.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void editFileCall_resetsCounter() {
        Content.ToolUseContent editFile =
                new Content.ToolUseContent(
                        "tool-1",
                        "edit_file",
                        Map.of("path", "Foo.java", "originalText", "old", "newText", "new"));

        NoWriteDetectedHook hook = new NoWriteDetectedHook(2, false);

        hook.onPostReasoning(eventWithToolCalls(bash("ls")));

        // Second turn: uses edit_file, counter resets
        HookResult<PostReasoningEvent> r2 = hook.onPostReasoning(eventWithToolCalls(editFile));
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);

        // Need 2 more turns to trigger
        hook.onPostReasoning(eventWithToolCalls(bash("ls")));
        HookResult<PostReasoningEvent> r4 = hook.onPostReasoning(eventWithToolCalls(bash("ls")));
        assertThat(r4.decision()).isEqualTo(HookResult.Decision.INJECT);
    }

    @Test
    void emptyToolCalls_doesNotTrigger() {
        NoWriteDetectedHook hook = new NoWriteDetectedHook(1, false);
        ModelResponse response =
                new ModelResponse(
                        "resp-1",
                        List.of(new Content.TextContent("I'll think about this.")),
                        null,
                        ModelResponse.StopReason.END_TURN,
                        "gpt-4o");
        PostReasoningEvent event = new PostReasoningEvent(response, false);

        HookResult<PostReasoningEvent> r = hook.onPostReasoning(event);
        assertThat(r.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void nullResponse_doesNotTrigger() {
        NoWriteDetectedHook hook = new NoWriteDetectedHook(1, false);
        PostReasoningEvent event = new PostReasoningEvent(null, false);

        HookResult<PostReasoningEvent> r = hook.onPostReasoning(event);
        assertThat(r.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }
}
