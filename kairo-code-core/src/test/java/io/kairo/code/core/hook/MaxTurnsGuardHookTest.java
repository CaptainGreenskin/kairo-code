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
import io.kairo.api.hook.ToolResultEvent;
import io.kairo.api.message.Content;
import io.kairo.api.model.ModelResponse;
import io.kairo.api.tool.ToolResult;
import io.kairo.code.core.stats.TurnMetricsCollector;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaxTurnsGuardHookTest {

    /** Build a PostReasoningEvent for a turn with tool calls. */
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

    /** Build a ToolResultEvent simulating a tool call. */
    private static ToolResultEvent toolEvent(String tool, boolean success, long millis) {
        ToolResult result = success ? ToolResult.success("id1", "output") : ToolResult.error("id1", "output");
        return new ToolResultEvent(tool, result, Duration.ofMillis(millis), success);
    }

    /**
     * Advance the TurnMetricsCollector by one turn: fire a tool result event,
     * then fire a post-reasoning event to close the turn.
     */
    private static void advanceTurn(TurnMetricsCollector collector) {
        collector.onToolResult(toolEvent("bash", true, 50));
        collector.onPostReasoning(eventWithToolCalls());
    }

    @Test
    void turnsBelowWarn_doesNotInject() {
        TurnMetricsCollector metrics = new TurnMetricsCollector();
        MaxTurnsGuardHook hook = new MaxTurnsGuardHook(metrics, 15, 20);

        // Advance to turn 10 (below warn threshold of 15)
        for (int i = 0; i < 10; i++) {
            advanceTurn(metrics);
        }

        HookResult<PostReasoningEvent> result = hook.onPostReasoning(eventWithToolCalls());

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void turnsAtWarnThreshold_injectsWarnMessage() {
        TurnMetricsCollector metrics = new TurnMetricsCollector();
        MaxTurnsGuardHook hook = new MaxTurnsGuardHook(metrics, 15, 20);

        for (int i = 0; i < 15; i++) {
            advanceTurn(metrics);
        }

        assertThat(metrics.totalTurns()).isEqualTo(15);
        HookResult<PostReasoningEvent> result = hook.onPostReasoning(eventWithToolCalls());

        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
        assertThat(result.injectedMessage().text())
                .contains("You have used 15 turns");
        assertThat(result.injectedMessage().text())
                .contains("Start wrapping up");
        assertThat(result.hookSource()).isEqualTo("MaxTurnsGuardHook");
    }

    @Test
    void warnAlreadyFired_noRepeatInjection() {
        TurnMetricsCollector metrics = new TurnMetricsCollector();
        MaxTurnsGuardHook hook = new MaxTurnsGuardHook(metrics, 15, 20);

        // Reach warn threshold
        for (int i = 0; i < 15; i++) {
            advanceTurn(metrics);
        }

        // First call: should inject warn
        HookResult<PostReasoningEvent> r1 = hook.onPostReasoning(eventWithToolCalls());
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);

        // Second call at same turn count: should NOT inject again
        HookResult<PostReasoningEvent> r2 = hook.onPostReasoning(eventWithToolCalls());
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r2.injectedMessage()).isNull();
    }

    @Test
    void turnsAtForceThreshold_injectsForceMessage() {
        TurnMetricsCollector metrics = new TurnMetricsCollector();
        MaxTurnsGuardHook hook = new MaxTurnsGuardHook(metrics, 15, 20);

        for (int i = 0; i < 20; i++) {
            advanceTurn(metrics);
        }

        assertThat(metrics.totalTurns()).isEqualTo(20);
        HookResult<PostReasoningEvent> result = hook.onPostReasoning(eventWithToolCalls());

        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
        assertThat(result.injectedMessage().text())
                .contains("You have used 20 turns");
        assertThat(result.injectedMessage().text())
                .contains("STOP");
        assertThat(result.injectedMessage().text())
                .contains("maximum allowed");
        assertThat(result.hookSource()).isEqualTo("MaxTurnsGuardHook");
    }

    @Test
    void forceAlreadyFired_noRepeatInjection() {
        TurnMetricsCollector metrics = new TurnMetricsCollector();
        MaxTurnsGuardHook hook = new MaxTurnsGuardHook(metrics, 15, 20);

        for (int i = 0; i < 20; i++) {
            advanceTurn(metrics);
        }

        // First call: should inject force
        HookResult<PostReasoningEvent> r1 = hook.onPostReasoning(eventWithToolCalls());
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(r1.injectedMessage().text()).contains("STOP");

        // Second call: should NOT inject again
        HookResult<PostReasoningEvent> r2 = hook.onPostReasoning(eventWithToolCalls());
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r2.injectedMessage()).isNull();
    }

    @Test
    void warnFiredBeforeForce_forceStillFiresAtThreshold() {
        TurnMetricsCollector metrics = new TurnMetricsCollector();
        MaxTurnsGuardHook hook = new MaxTurnsGuardHook(metrics, 15, 20);

        // Reach warn threshold and fire it
        for (int i = 0; i < 15; i++) {
            advanceTurn(metrics);
        }
        HookResult<PostReasoningEvent> warnResult = hook.onPostReasoning(eventWithToolCalls());
        assertThat(warnResult.decision()).isEqualTo(HookResult.Decision.INJECT);

        // Advance to force threshold
        for (int i = 15; i < 20; i++) {
            advanceTurn(metrics);
        }

        HookResult<PostReasoningEvent> forceResult = hook.onPostReasoning(eventWithToolCalls());
        assertThat(forceResult.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(forceResult.injectedMessage().text()).contains("STOP");
    }

    @Test
    void forceFiresBeforeWarn_whenSkippingPastWarn() {
        // If turns jump from below warn to at/above force in one step,
        // force should fire and warn should be suppressed.
        TurnMetricsCollector metrics = new TurnMetricsCollector();
        MaxTurnsGuardHook hook = new MaxTurnsGuardHook(metrics, 15, 20);

        for (int i = 0; i < 20; i++) {
            advanceTurn(metrics);
        }

        // First call should be force (checked before warn)
        HookResult<PostReasoningEvent> result = hook.onPostReasoning(eventWithToolCalls());
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage().text()).contains("STOP");

        // Warn should also be suppressed (force sets warnFired = true)
        advanceTurn(metrics);
        HookResult<PostReasoningEvent> r2 = hook.onPostReasoning(eventWithToolCalls());
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void defaultConstructor_uses20and30() {
        TurnMetricsCollector metrics = new TurnMetricsCollector();
        MaxTurnsGuardHook hook = new MaxTurnsGuardHook(metrics);

        // At 19 turns: no injection
        for (int i = 0; i < 19; i++) {
            advanceTurn(metrics);
        }
        assertThat(hook.onPostReasoning(eventWithToolCalls()).decision())
                .isEqualTo(HookResult.Decision.CONTINUE);

        // At 20 turns: warn fires
        advanceTurn(metrics);
        assertThat(hook.onPostReasoning(eventWithToolCalls()).decision())
                .isEqualTo(HookResult.Decision.INJECT);
    }
}
