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
import io.kairo.api.hook.PreReasoningEvent;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextCompactionHookTest {

    private static final int MAX_TOKENS = 100_000;
    private static final double THRESHOLD = 0.85;
    private static final double THRESHOLD_80 = 0.80;

    private static PreReasoningEvent eventWithMessages(List<Msg> messages) {
        ModelConfig config = ModelConfig.builder().model("gpt-4o").build();
        return new PreReasoningEvent(List.copyOf(messages), config, false);
    }

    private static PreReasoningEvent eventWithCharCount(int charCount) {
        // ~4 chars per token, so charCount chars ~ charCount/4 tokens
        String text = "x".repeat(charCount);
        List<Msg> messages = List.of(Msg.of(MsgRole.USER, text));
        return eventWithMessages(messages);
    }

    @Test
    void belowThreshold_doesNotTrigger() {
        ContextCompactionHook hook = new ContextCompactionHook(MAX_TOKENS, THRESHOLD, false);
        // 85% of 100k = 85k tokens trigger. chars/3.5: 294000 chars → 84000 tokens (below threshold)
        PreReasoningEvent event = eventWithCharCount(294_000);
        HookResult<PreReasoningEvent> result = hook.onPreReasoning(event);
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void atThreshold_triggersInjection() {
        ContextCompactionHook hook = new ContextCompactionHook(MAX_TOKENS, THRESHOLD, false);
        // 85% of 100k = 85k tokens. chars/3.5: 297500 chars → 85000 tokens
        PreReasoningEvent event = eventWithCharCount(297_500);
        HookResult<PreReasoningEvent> result = hook.onPreReasoning(event);
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
        assertThat(result.injectedMessage().text()).contains("summarize");
    }

    @Test
    void aboveThreshold_triggersInjection() {
        ContextCompactionHook hook = new ContextCompactionHook(MAX_TOKENS, THRESHOLD, false);
        // 90% of 100k = 90k tokens. chars/3.5: 315000 chars → 90000 tokens
        PreReasoningEvent event = eventWithCharCount(315_000);
        HookResult<PreReasoningEvent> result = hook.onPreReasoning(event);
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
    }

    @Test
    void secondCall_suppressedDuringCooling() {
        ContextCompactionHook hook = new ContextCompactionHook(MAX_TOKENS, THRESHOLD, false);
        // First call: triggers, compactionCount = 1
        PreReasoningEvent event1 = eventWithCharCount(297_500);
        HookResult<PreReasoningEvent> r1 = hook.onPreReasoning(event1);
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(hook.getCompactionCount()).isEqualTo(1);

        // Second call immediately after: cooling period suppresses trigger
        PreReasoningEvent event2 = eventWithCharCount(297_500);
        HookResult<PreReasoningEvent> r2 = hook.onPreReasoning(event2);
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void multiRound_triggersAgainAfterCooling() {
        // Use coolingTurns=2 for fast test
        ContextCompactionHook hook = new ContextCompactionHook(MAX_TOKENS, THRESHOLD_80, false, 2);
        PreReasoningEvent overThreshold = eventWithCharCount(280_000); // 80k tokens = exactly 80%

        // First compaction
        assertThat(hook.onPreReasoning(overThreshold).decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(hook.getCompactionCount()).isEqualTo(1);

        // Cooling turn 1: suppressed
        assertThat(hook.onPreReasoning(overThreshold).decision()).isEqualTo(HookResult.Decision.CONTINUE);
        // Cooling turn 2: suppressed (turnsSinceLastCompaction = 2 is NOT >= coolingTurns=2 because
        // we need strictly >= coolingTurns; but with coolingTurns=2, turn 2 means 2 >= 2 → triggers)
        // Actually: reset to 0, then +1 each call. So call1→1 (1<2 → skip), call2→2 (2>=2 → trigger)
        assertThat(hook.onPreReasoning(overThreshold).decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(hook.getCompactionCount()).isEqualTo(2);
    }

    @Test
    void multiRound_threeCompactions() {
        ContextCompactionHook hook = new ContextCompactionHook(MAX_TOKENS, THRESHOLD_80, false, 2);
        PreReasoningEvent overThreshold = eventWithCharCount(280_000);

        // Compaction 1
        hook.onPreReasoning(overThreshold);
        assertThat(hook.getCompactionCount()).isEqualTo(1);

        // 2 cooling turns → compaction 2
        hook.onPreReasoning(overThreshold);
        hook.onPreReasoning(overThreshold);
        assertThat(hook.getCompactionCount()).isEqualTo(2);

        // 2 cooling turns → compaction 3
        hook.onPreReasoning(overThreshold);
        hook.onPreReasoning(overThreshold);
        assertThat(hook.getCompactionCount()).isEqualTo(3);
    }

    @Test
    void initialState_allowsImmediateCompaction() {
        // turnsSinceLastCompaction starts at coolingTurns, so first call fires immediately
        ContextCompactionHook hook = new ContextCompactionHook(MAX_TOKENS, THRESHOLD, false, 5);
        PreReasoningEvent overThreshold = eventWithCharCount(297_500);
        assertThat(hook.onPreReasoning(overThreshold).decision()).isEqualTo(HookResult.Decision.INJECT);
    }

    @Test
    void replMode_doesNotTrigger() {
        ContextCompactionHook hook = new ContextCompactionHook(MAX_TOKENS, THRESHOLD, true);
        PreReasoningEvent event = eventWithCharCount(315_000);
        HookResult<PreReasoningEvent> result = hook.onPreReasoning(event);
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void customThreshold_respected() {
        // Set threshold to 0.50 -> trigger at 50k tokens = 175k chars (chars/3.5)
        ContextCompactionHook hook = new ContextCompactionHook(MAX_TOKENS, 0.50, false);
        PreReasoningEvent event = eventWithCharCount(175_000);
        HookResult<PreReasoningEvent> result = hook.onPreReasoning(event);
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
    }

    @Test
    void smallContext_doesNotTrigger() {
        ContextCompactionHook hook = new ContextCompactionHook(MAX_TOKENS, THRESHOLD, false);
        List<Msg> messages = new ArrayList<>();
        messages.add(Msg.of(MsgRole.SYSTEM, "You are a helpful assistant."));
        messages.add(Msg.of(MsgRole.USER, "Hello"));
        PreReasoningEvent event = eventWithMessages(messages);
        HookResult<PreReasoningEvent> result = hook.onPreReasoning(event);
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void estimateTokens_matchesContextWindowGuardLogic() {
        ContextCompactionHook hook = new ContextCompactionHook(MAX_TOKENS, THRESHOLD, false);
        // 350 chars * 2/7 = 100 tokens (chars/3.5 coefficient for TextContent)
        String text = "a".repeat(350);
        List<Msg> messages = List.of(Msg.of(MsgRole.USER, text));
        assertThat(hook.estimateTokens(messages)).isEqualTo(100);
    }

    @Test
    void estimateTokens_withToolUseContent() {
        ContextCompactionHook hook = new ContextCompactionHook(MAX_TOKENS, THRESHOLD, false);
        String input = "arg1=value1,arg2=value2";
        Content.ToolUseContent toolUse = new Content.ToolUseContent(
                "tool-1", "bash", java.util.Map.of("command", input));
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).addContent(toolUse).build();
        List<Msg> messages = List.of(msg);
        // input.toString() for Map is roughly "{command=arg1=value1,arg2=value2}" ~ 38 chars
        int estimated = hook.estimateTokens(messages);
        assertThat(estimated).isGreaterThan(0);
    }

    @Test
    void estimateTokens_withToolResultContent() {
        ContextCompactionHook hook = new ContextCompactionHook(MAX_TOKENS, THRESHOLD, false);
        String result = "command output here";
        Content.ToolResultContent toolResult = new Content.ToolResultContent("tool-1", result, false);
        Msg msg = Msg.builder().role(MsgRole.USER).addContent(toolResult).build();
        List<Msg> messages = List.of(msg);
        int estimated = hook.estimateTokens(messages);
        assertThat(estimated).isGreaterThan(0);
    }

    @Test
    void estimateTokens_withThinkingContent() {
        ContextCompactionHook hook = new ContextCompactionHook(MAX_TOKENS, THRESHOLD, false);
        String thinking = "Let me think about this carefully...";
        Content.ThinkingContent tc = new Content.ThinkingContent(thinking, 0, "");
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).addContent(tc).build();
        List<Msg> messages = List.of(msg);
        int estimated = hook.estimateTokens(messages);
        assertThat(estimated).isGreaterThan(0);
    }
}
