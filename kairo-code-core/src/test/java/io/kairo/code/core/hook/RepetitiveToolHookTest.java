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

class RepetitiveToolHookTest {

    /** Build a PostReasoningEvent with the given tool calls. */
    private static PostReasoningEvent eventWithTools(String... toolNames) {
        List<Content> contents = new java.util.ArrayList<>();
        for (String name : toolNames) {
            contents.add(new Content.ToolUseContent("id-" + name, name, Map.of()));
        }
        ModelResponse response =
                new ModelResponse("resp-1", contents, null,
                        ModelResponse.StopReason.TOOL_USE, "gpt-4o");
        return new PostReasoningEvent(response, false);
    }

    /** Build a PostReasoningEvent with no tool calls (text only). */
    private static PostReasoningEvent eventWithText() {
        ModelResponse response =
                new ModelResponse("resp-1",
                        List.of(new Content.TextContent("I think we should...")),
                        null, ModelResponse.StopReason.END_TURN, "gpt-4o");
        return new PostReasoningEvent(response, false);
    }

    @Test
    void belowThreshold_continues() {
        // threshold=4, so 3 consecutive bash calls should not trigger
        RepetitiveToolHook hook = new RepetitiveToolHook(false, 4);

        for (int i = 0; i < 3; i++) {
            HookResult<PostReasoningEvent> result = hook.onPostReasoning(eventWithTools("bash"));
            assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        }
    }

    @Test
    void atThreshold_injectsHint() {
        RepetitiveToolHook hook = new RepetitiveToolHook(false, 4);

        // 4 consecutive bash calls
        for (int i = 0; i < 3; i++) {
            hook.onPostReasoning(eventWithTools("bash"));
        }

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(eventWithTools("bash"));

        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
        assertThat(result.injectedMessage().text()).contains("bash");
        assertThat(result.injectedMessage().text()).contains("4 times in a row");
        assertThat(result.hookSource()).isEqualTo("RepetitiveToolHook");
    }

    @Test
    void alreadyFired_suppressedForSameTool() {
        RepetitiveToolHook hook = new RepetitiveToolHook(false, 3);

        // Reach threshold and fire
        hook.onPostReasoning(eventWithTools("grep"));
        hook.onPostReasoning(eventWithTools("grep"));
        assertThat(hook.onPostReasoning(eventWithTools("grep")).decision())
                .isEqualTo(HookResult.Decision.INJECT);

        // Continue with same tool — should NOT inject again
        HookResult<PostReasoningEvent> result = hook.onPostReasoning(eventWithTools("grep"));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void toolSwitch_resetsCount() {
        RepetitiveToolHook hook = new RepetitiveToolHook(false, 4);

        // 3 bash calls
        for (int i = 0; i < 3; i++) {
            hook.onPostReasoning(eventWithTools("bash"));
        }

        // Switch to grep — resets count
        hook.onPostReasoning(eventWithTools("grep"));

        // 3 more grep calls (count=1 after switch, so total 3 grep)
        for (int i = 0; i < 2; i++) {
            HookResult<PostReasoningEvent> result = hook.onPostReasoning(eventWithTools("grep"));
            assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        }

        // 4th grep — should trigger
        HookResult<PostReasoningEvent> result = hook.onPostReasoning(eventWithTools("grep"));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage().text()).contains("grep");
    }

    @Test
    void differentTools_eachCanTriggerOnce() {
        RepetitiveToolHook hook = new RepetitiveToolHook(false, 3);

        // Tool A: 3 consecutive → fires
        for (int i = 0; i < 2; i++) {
            hook.onPostReasoning(eventWithTools("bash"));
        }
        assertThat(hook.onPostReasoning(eventWithTools("bash")).decision())
                .isEqualTo(HookResult.Decision.INJECT);

        // Tool B: 3 consecutive → also fires independently
        for (int i = 0; i < 2; i++) {
            hook.onPostReasoning(eventWithTools("read_file"));
        }
        assertThat(hook.onPostReasoning(eventWithTools("read_file")).decision())
                .isEqualTo(HookResult.Decision.INJECT);
        assertThat(hook.onPostReasoning(eventWithTools("read_file")).decision())
                .isEqualTo(HookResult.Decision.CONTINUE); // suppressed for read_file
    }

    @Test
    void singleTurnMultipleTools_usesFirstTool() {
        // When a turn has multiple tool calls, only the first one is tracked
        RepetitiveToolHook hook = new RepetitiveToolHook(false, 3);

        // 3 turns, each with "bash" as first tool (and "grep" as second)
        for (int i = 0; i < 2; i++) {
            hook.onPostReasoning(eventWithTools("bash", "grep"));
        }

        // 3rd turn — bash is first, so it should trigger
        HookResult<PostReasoningEvent> result =
                hook.onPostReasoning(eventWithTools("bash", "grep"));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage().text()).contains("bash");
    }

    @Test
    void replMode_noInjection() {
        RepetitiveToolHook hook = new RepetitiveToolHook(true, 3);

        // 10 consecutive calls — should never inject in REPL
        for (int i = 0; i < 10; i++) {
            HookResult<PostReasoningEvent> result = hook.onPostReasoning(eventWithTools("bash"));
            assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
            assertThat(result.injectedMessage()).isNull();
        }
    }

    @Test
    void noToolCall_resetsStreak() {
        RepetitiveToolHook hook = new RepetitiveToolHook(false, 3);

        // 2 bash calls
        hook.onPostReasoning(eventWithTools("bash"));
        hook.onPostReasoning(eventWithTools("bash"));

        // No tool call this turn — resets streak
        hook.onPostReasoning(eventWithText());

        // 2 more bash calls — count starts from 1
        hook.onPostReasoning(eventWithTools("bash"));
        HookResult<PostReasoningEvent> result = hook.onPostReasoning(eventWithTools("bash"));
        // This is only the 2nd bash after reset, so no injection yet
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void extractFirstToolName_nullResponse_returnsNull() {
        assertThat(RepetitiveToolHook.extractFirstToolName(null)).isNull();
    }

    @Test
    void extractFirstToolName_emptyContents_returnsNull() {
        ModelResponse response = new ModelResponse("r", List.of(), null,
                ModelResponse.StopReason.END_TURN, "gpt-4o");
        assertThat(RepetitiveToolHook.extractFirstToolName(response)).isNull();
    }
}
