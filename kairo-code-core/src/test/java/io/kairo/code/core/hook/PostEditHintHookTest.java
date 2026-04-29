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

class PostEditHintHookTest {

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

    private static Content.ToolUseContent writeFile(String path) {
        return new Content.ToolUseContent("tool-1", "write", Map.of("path", path, "content", "code"));
    }

    private static Content.ToolUseContent editFile(String path) {
        return new Content.ToolUseContent("tool-1", "edit", Map.of("path", path, "old_string", "old", "new_string", "new"));
    }

    private static Content.ToolUseContent bash(String command) {
        return new Content.ToolUseContent("tool-2", "bash", Map.of("command", command));
    }

    @Test
    void writeFile_javaFile_triggersInject() {
        PostEditHintHook hook = new PostEditHintHook(false);
        PostReasoningEvent event = eventWithToolCalls(writeFile("src/main/java/Foo.java"));

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
        assertThat(result.injectedMessage().text()).contains("mvn test");
    }

    @Test
    void editFile_javaFile_triggersInject() {
        PostEditHintHook hook = new PostEditHintHook(false);
        PostReasoningEvent event = eventWithToolCalls(editFile("src/main/java/Bar.java"));

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
    }

    @Test
    void writeFile_nonJavaFile_doesNotTrigger() {
        PostEditHintHook hook = new PostEditHintHook(false);
        PostReasoningEvent event = eventWithToolCalls(writeFile("README.md"));

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void bash_doesNotTrigger() {
        PostEditHintHook hook = new PostEditHintHook(false);
        PostReasoningEvent event = eventWithToolCalls(bash("mvn test"));

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void replMode_doesNotTrigger() {
        PostEditHintHook hook = new PostEditHintHook(true);
        PostReasoningEvent event = eventWithToolCalls(writeFile("src/main/java/Foo.java"));

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }
}
