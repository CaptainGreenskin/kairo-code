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

class PlanWithoutActionHookTest {

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

    private static Content.ToolUseContent todoWrite(Map<String, Object> input) {
        return new Content.ToolUseContent("tool-1", "todo_write", input);
    }

    private static Content.ToolUseContent bash(String command) {
        return new Content.ToolUseContent("tool-2", "bash", Map.of("command", command));
    }

    private static Content.ToolUseContent readFile(String path) {
        return new Content.ToolUseContent("tool-2", "read_file", Map.of("path", path));
    }

    @Test
    void todoWriteOnly_triggersInject() {
        PlanWithoutActionHook hook = new PlanWithoutActionHook(2, false);
        PostReasoningEvent event =
                eventWithToolCalls(todoWrite(Map.of("todos", List.of("do something"))));

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
        assertThat(result.injectedMessage().text())
                .contains("did not use any implementation tools");
    }

    @Test
    void todoWriteWithBash_doesNotTrigger() {
        PlanWithoutActionHook hook = new PlanWithoutActionHook(2, false);
        PostReasoningEvent event =
                eventWithToolCalls(
                        todoWrite(Map.of("todos", List.of("do something"))),
                        bash("echo hello"));

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void bashOnly_doesNotTrigger() {
        PlanWithoutActionHook hook = new PlanWithoutActionHook(2, false);
        PostReasoningEvent event = eventWithToolCalls(bash("mvn test"));

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void readFileOnly_doesNotTrigger() {
        PlanWithoutActionHook hook = new PlanWithoutActionHook(2, false);
        PostReasoningEvent event = eventWithToolCalls(readFile("src/main/java/Foo.java"));

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void thirdInjection_returnsContinue() {
        PlanWithoutActionHook hook = new PlanWithoutActionHook(2, false);
        PostReasoningEvent event =
                eventWithToolCalls(todoWrite(Map.of("todos", List.of("step"))));

        // First injection
        HookResult<PostReasoningEvent> r1 = hook.onPostReasoning(event);
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);

        // Second injection
        HookResult<PostReasoningEvent> r2 = hook.onPostReasoning(event);
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.INJECT);

        // Third attempt: should stop injecting
        HookResult<PostReasoningEvent> r3 = hook.onPostReasoning(event);
        assertThat(r3.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r3.injectedMessage()).isNull();
    }

    @Test
    void replMode_doesNotTrigger() {
        PlanWithoutActionHook hook = new PlanWithoutActionHook(2, true);
        PostReasoningEvent event =
                eventWithToolCalls(todoWrite(Map.of("todos", List.of("do something"))));

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void todoReadOnly_triggersInject() {
        Content.ToolUseContent todoRead =
                new Content.ToolUseContent("tool-1", "todo_read", Map.of());
        PlanWithoutActionHook hook = new PlanWithoutActionHook(2, false);
        PostReasoningEvent event = eventWithToolCalls(todoRead);

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
    }

    @Test
    void textOnlyResponse_doesNotTrigger() {
        PlanWithoutActionHook hook = new PlanWithoutActionHook(2, false);
        ModelResponse response =
                new ModelResponse(
                        "resp-1",
                        List.of(new Content.TextContent("I'll start working on this.")),
                        null,
                        ModelResponse.StopReason.END_TURN,
                        "gpt-4o");
        PostReasoningEvent event = new PostReasoningEvent(response, false);

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void nullResponse_doesNotTrigger() {
        PlanWithoutActionHook hook = new PlanWithoutActionHook(2, false);
        PostReasoningEvent event = new PostReasoningEvent(null, false);

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }
}
