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

class PostBatchEditVerifyHookTest {

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

    private static PostReasoningEvent emptyTurnEvent() {
        ModelResponse response =
                new ModelResponse(
                        "resp-1",
                        List.of(new Content.TextContent("Looking at the code...")),
                        null,
                        ModelResponse.StopReason.END_TURN,
                        "gpt-4o");
        return new PostReasoningEvent(response, false);
    }

    private static Content.ToolUseContent writeFile(String path) {
        return new Content.ToolUseContent(
                "tool-1", "write_file", Map.of("path", path, "content", "code"));
    }

    private static Content.ToolUseContent editFile(String path) {
        return new Content.ToolUseContent(
                "tool-1", "edit_file", Map.of("path", path, "old_string", "old", "new_string", "new"));
    }

    private static Content.ToolUseContent bash(String command) {
        return new Content.ToolUseContent("tool-2", "bash", Map.of("command", command));
    }

    @Test
    void consecutiveJavaEditsWithoutBash_injectsOnSecondIdleTurn() {
        PostBatchEditVerifyHook hook = new PostBatchEditVerifyHook(false);

        // Turn 1: edit Java file → turnsSinceEdit = 1
        PostReasoningEvent editTurn = eventWithToolCalls(editFile("src/main/java/Cache.java"));
        HookResult<PostReasoningEvent> r1 = hook.onPostReasoning(editTurn);
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.CONTINUE);

        // Turn 2: idle (no bash, no edit) → turnsSinceEdit = 2 → inject
        PostReasoningEvent idleTurn = emptyTurnEvent();
        HookResult<PostReasoningEvent> r2 = hook.onPostReasoning(idleTurn);

        assertThat(r2.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(r2.injectedMessage()).isNotNull();
        assertThat(r2.injectedMessage().text()).contains("mvn test");
    }

    @Test
    void bashCall_resetsCounter() {
        PostBatchEditVerifyHook hook = new PostBatchEditVerifyHook(false);

        // Turn 1: edit Java file
        hook.onPostReasoning(eventWithToolCalls(editFile("src/main/java/Foo.java")));

        // Turn 2: bash call — resets counter
        hook.onPostReasoning(eventWithToolCalls(bash("mvn test")));

        // Turn 3: idle — should NOT inject because counter was reset
        HookResult<PostReasoningEvent> r3 = hook.onPostReasoning(emptyTurnEvent());
        assertThat(r3.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void replMode_doesNotInject() {
        PostBatchEditVerifyHook hook = new PostBatchEditVerifyHook(true);

        // Turn 1: edit
        hook.onPostReasoning(eventWithToolCalls(editFile("src/main/java/Foo.java")));

        // Turn 2: idle — should NOT inject in REPL mode
        HookResult<PostReasoningEvent> r2 = hook.onPostReasoning(emptyTurnEvent());
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r2.injectedMessage()).isNull();
    }

    @Test
    void maxThreeInjections_thenStops() {
        PostBatchEditVerifyHook hook = new PostBatchEditVerifyHook(false);

        for (int cycle = 0; cycle < 4; cycle++) {
            // Edit → turnsSinceEdit = 1
            hook.onPostReasoning(eventWithToolCalls(editFile("src/main/java/F" + cycle + ".java")));

            // Idle turn → turnsSinceEdit = 2 → should inject (up to 3 times)
            HookResult<PostReasoningEvent> result = hook.onPostReasoning(emptyTurnEvent());

            if (cycle < 3) {
                assertThat(result.decision())
                        .as("Cycle %d should inject", cycle)
                        .isEqualTo(HookResult.Decision.INJECT);
            } else {
                assertThat(result.decision())
                        .as("Cycle 3 should NOT inject (max reached)")
                        .isEqualTo(HookResult.Decision.CONTINUE);
                assertThat(result.injectedMessage()).isNull();
            }
        }
    }

    @Test
    void nonJavaEdit_doesNotTrigger() {
        PostBatchEditVerifyHook hook = new PostBatchEditVerifyHook(false);

        // Edit a non-Java file — turnsSinceEdit stays 0
        hook.onPostReasoning(eventWithToolCalls(writeFile("README.md")));

        // Idle turn — should NOT inject because no Java edits
        HookResult<PostReasoningEvent> r = hook.onPostReasoning(emptyTurnEvent());
        assertThat(r.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void idleTurnWithoutPriorEdits_doesNotTrigger() {
        PostBatchEditVerifyHook hook = new PostBatchEditVerifyHook(false);

        // No edits, just idle turns
        HookResult<PostReasoningEvent> r1 = hook.onPostReasoning(emptyTurnEvent());
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.CONTINUE);

        HookResult<PostReasoningEvent> r2 = hook.onPostReasoning(emptyTurnEvent());
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }
}
