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
import io.kairo.api.hook.PreActingEvent;
import io.kairo.api.model.ModelResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanModeHookTest {

    private static PostReasoningEvent reasoningEvent(String text) {
        ModelResponse responseWithText = new ModelResponse(
                "resp-1",
                List.of(new io.kairo.api.message.Content.TextContent(text)),
                null,
                ModelResponse.StopReason.END_TURN,
                "gpt-4o");
        return new PostReasoningEvent(responseWithText, false);
    }

    private static PreActingEvent preActingEvent() {
        return new PreActingEvent("bash", Map.of("command", "ls"), false);
    }

    @Test
    void disabled_preActingProceedsImmediately() {
        PlanModeHook hook = new PlanModeHook(false);

        HookResult<PreActingEvent> result = hook.onPreActing(preActingEvent());

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(hook.wasRejected()).isFalse();
    }

    @Test
    void disabled_postReasoningDoesNotCache() {
        PlanModeHook hook = new PlanModeHook(false);

        hook.onPostReasoning(reasoningEvent("do something"));
        // When disabled, the hook should not affect the result.
        HookResult<PostReasoningEvent> result = hook.onPostReasoning(reasoningEvent("plan text"));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void postReasoning_cachesReasoningText() {
        PlanModeHook hook = new PlanModeHook(true, new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8)));

        hook.onPostReasoning(reasoningEvent("Step 1: Read the file\nStep 2: Edit it"));

        // POST_REASONING should always proceed — caching is internal.
        HookResult<PostReasoningEvent> result = hook.onPostReasoning(reasoningEvent("cached text"));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void preActing_afterPlanConfirmed_subsequentCallsProceed() {
        ByteArrayInputStream stdin = new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8));
        PlanModeHook hook = new PlanModeHook(true, stdin);

        // First: POST_REASONING caches the text.
        hook.onPostReasoning(reasoningEvent("My plan is to edit the file."));

        // Second: First PRE_ACTING prompts user, user says "y" -> proceed.
        HookResult<PreActingEvent> r1 = hook.onPreActing(preActingEvent());
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(hook.wasRejected()).isFalse();

        // Third: Second PRE_ACTING should proceed without prompting (plan already confirmed).
        HookResult<PreActingEvent> r2 = hook.onPreActing(preActingEvent());
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void preActing_userAccepts_y() {
        ByteArrayInputStream stdin = new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8));
        PlanModeHook hook = new PlanModeHook(true, stdin);

        hook.onPostReasoning(reasoningEvent("Plan: edit the config file"));

        HookResult<PreActingEvent> result = hook.onPreActing(preActingEvent());

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(hook.wasRejected()).isFalse();
    }

    @Test
    void preActing_userAccepts_Y() {
        ByteArrayInputStream stdin = new ByteArrayInputStream("Y\n".getBytes(StandardCharsets.UTF_8));
        PlanModeHook hook = new PlanModeHook(true, stdin);

        hook.onPostReasoning(reasoningEvent("Plan: edit the config file"));

        HookResult<PreActingEvent> result = hook.onPreActing(preActingEvent());

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(hook.wasRejected()).isFalse();
    }

    @Test
    void preActing_userRejects_n() {
        ByteArrayInputStream stdin = new ByteArrayInputStream("n\n".getBytes(StandardCharsets.UTF_8));
        PlanModeHook hook = new PlanModeHook(true, stdin);

        hook.onPostReasoning(reasoningEvent("Plan: delete all files"));

        HookResult<PreActingEvent> result = hook.onPreActing(preActingEvent());

        assertThat(result.decision()).isEqualTo(HookResult.Decision.ABORT);
        assertThat(result.reason()).contains("Plan rejected");
        assertThat(hook.wasRejected()).isTrue();
    }

    @Test
    void preActing_userRejects_emptyInput() {
        // Empty input (just newline) should default to rejection (N).
        ByteArrayInputStream stdin = new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8));
        PlanModeHook hook = new PlanModeHook(true, stdin);

        hook.onPostReasoning(reasoningEvent("Plan: run tests"));

        HookResult<PreActingEvent> result = hook.onPreActing(preActingEvent());

        assertThat(result.decision()).isEqualTo(HookResult.Decision.ABORT);
        assertThat(hook.wasRejected()).isTrue();
    }

    @Test
    void preActing_noReasoningText_showsPlaceholder() {
        // When no POST_REASONING happened before PRE_ACTING (edge case).
        ByteArrayInputStream stdin = new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8));
        PlanModeHook hook = new PlanModeHook(true, stdin);

        HookResult<PreActingEvent> result = hook.onPreActing(preActingEvent());

        // Should still proceed on "y" even without cached reasoning text.
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }
}
