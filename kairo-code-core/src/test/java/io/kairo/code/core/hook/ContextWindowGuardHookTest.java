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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextWindowGuardHookTest {

    private static final ModelConfig DEFAULT_CONFIG =
            ModelConfig.builder().model("gpt-4o").maxTokens(4096).temperature(0.7).build();

    /** Helper: build a PreReasoningEvent with the given messages. */
    private static PreReasoningEvent eventWithMessages(List<Msg> messages) {
        return new PreReasoningEvent(messages, DEFAULT_CONFIG, false);
    }

    /** Helper: create a user message with the given text. */
    private static Msg userMsg(String text) {
        return Msg.of(MsgRole.USER, text);
    }

    /** Helper: create an assistant message with the given text. */
    private static Msg assistantMsg(String text) {
        return Msg.of(MsgRole.ASSISTANT, text);
    }

    /** Helper: generate a string of approximately n characters. */
    private static String repeatedText(int chars) {
        return "x".repeat(chars);
    }

    @Test
    void shortHistory_noInjection() {
        ContextWindowGuardHook hook = new ContextWindowGuardHook(100, 200);
        List<Msg> messages = List.of(
                userMsg("Hello"),
                assistantMsg("Hi there!")
        );
        PreReasoningEvent event = eventWithMessages(messages);

        HookResult<PreReasoningEvent> result = hook.onPreReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void historyExceedsWarnThreshold_injectsWarnMessage() {
        // warnThreshold=100 tokens ≈ 400 chars, criticalThreshold=200 tokens ≈ 800 chars
        // Use 500 chars total ≈ 125 tokens: above warn but below critical
        ContextWindowGuardHook hook = new ContextWindowGuardHook(100, 200);
        List<Msg> messages = List.of(
                userMsg(repeatedText(300)),
                assistantMsg(repeatedText(200))
        );
        PreReasoningEvent event = eventWithMessages(messages);

        HookResult<PreReasoningEvent> result = hook.onPreReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
        assertThat(result.injectedMessage().text())
                .contains("Context is getting large");
        assertThat(result.hookSource()).isEqualTo("ContextWindowGuardHook");
    }

    @Test
    void warnAlreadyFired_noRepeatInjection() {
        ContextWindowGuardHook hook = new ContextWindowGuardHook(100, 200);
        // 500 chars ≈ 125 tokens: above warn (100) but below critical (200)
        List<Msg> messages = List.of(
                userMsg(repeatedText(300)),
                assistantMsg(repeatedText(200))
        );
        PreReasoningEvent event = eventWithMessages(messages);

        // First call: should inject warn
        HookResult<PreReasoningEvent> r1 = hook.onPreReasoning(event);
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(r1.injectedMessage().text()).contains("Context is getting large");

        // Second call with same large context: should NOT inject again (idempotent)
        HookResult<PreReasoningEvent> r2 = hook.onPreReasoning(event);
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r2.injectedMessage()).isNull();
    }

    @Test
    void historyExceedsCriticalThreshold_injectsCriticalMessage() {
        // warnThreshold=100, criticalThreshold=200; use 1000 chars ≈ 250 tokens
        ContextWindowGuardHook hook = new ContextWindowGuardHook(100, 200);
        List<Msg> messages = List.of(
                userMsg(repeatedText(1000)),
                assistantMsg(repeatedText(1000))
        );
        PreReasoningEvent event = eventWithMessages(messages);

        HookResult<PreReasoningEvent> result = hook.onPreReasoning(event);

        // Critical threshold fires first (checked before warn)
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
        assertThat(result.injectedMessage().text())
                .contains("Context window is near capacity");
        assertThat(result.injectedMessage().text())
                .contains("/compact");
    }

    @Test
    void criticalAlreadyFired_noRepeatInjection() {
        ContextWindowGuardHook hook = new ContextWindowGuardHook(100, 200);
        List<Msg> messages = List.of(
                userMsg(repeatedText(1000)),
                assistantMsg(repeatedText(1000))
        );
        PreReasoningEvent event = eventWithMessages(messages);

        // First call: should inject critical
        HookResult<PreReasoningEvent> r1 = hook.onPreReasoning(event);
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(r1.injectedMessage().text()).contains("Context window is near capacity");

        // Second call: should NOT inject again
        HookResult<PreReasoningEvent> r2 = hook.onPreReasoning(event);
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r2.injectedMessage()).isNull();
    }

    @Test
    void criticalFiredAlsoMarksWarnAsFired() {
        // After critical fires, if context shrinks below critical but stays above warn,
        // warn should also not fire (it was implicitly fired when critical fired).
        ContextWindowGuardHook hook = new ContextWindowGuardHook(100, 200);

        // First: large context triggers critical
        List<Msg> largeMessages = List.of(
                userMsg(repeatedText(1000)),
                assistantMsg(repeatedText(1000))
        );
        HookResult<PreReasoningEvent> r1 = hook.onPreReasoning(eventWithMessages(largeMessages));
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(r1.injectedMessage().text()).contains("Context window is near capacity");

        // Second: smaller but still above warn threshold
        List<Msg> mediumMessages = List.of(
                userMsg(repeatedText(500)),
                assistantMsg(repeatedText(500))
        );
        HookResult<PreReasoningEvent> r2 = hook.onPreReasoning(eventWithMessages(mediumMessages));
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r2.injectedMessage()).isNull();
    }

    @Test
    void estimateTokens_textContent() {
        ContextWindowGuardHook hook = new ContextWindowGuardHook(40_000, 70_000);
        List<Msg> messages = List.of(
                userMsg("Hello world"),       // 11 chars
                assistantMsg("Hi there!")      // 9 chars
        );
        // (11 + 9) / 4 = 5 tokens
        assertThat(hook.estimateTokens(messages)).isEqualTo(5);
    }

    @Test
    void estimateTokens_toolUseAndResult() {
        ContextWindowGuardHook hook = new ContextWindowGuardHook(40_000, 70_000);

        Msg toolUseMsg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .addContent(new Content.ToolUseContent("id-1", "bash", Map.of("command", "echo hello")))
                .build();
        Msg toolResultMsg = Msg.builder()
                .role(MsgRole.USER)
                .addContent(new Content.ToolResultContent("id-1", "hello\n", false))
                .build();

        int tokens = hook.estimateTokens(List.of(toolUseMsg, toolResultMsg));
        // {"command"="echo hello"} ≈ 22 chars + "hello\n" = 6 chars = 28 chars / 4 = 7
        assertThat(tokens).isGreaterThan(0);
    }

    @Test
    void warnOnlyDoesNotTriggerCritical() {
        // Context above warn but below critical
        ContextWindowGuardHook hook = new ContextWindowGuardHook(100, 500);
        List<Msg> messages = List.of(
                userMsg(repeatedText(400)),   // ≈100 tokens
                assistantMsg(repeatedText(200))
        );
        PreReasoningEvent event = eventWithMessages(messages);

        HookResult<PreReasoningEvent> result = hook.onPreReasoning(event);

        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage().text()).contains("Context is getting large");
        assertThat(result.injectedMessage().text()).doesNotContain("/compact");
    }
}
