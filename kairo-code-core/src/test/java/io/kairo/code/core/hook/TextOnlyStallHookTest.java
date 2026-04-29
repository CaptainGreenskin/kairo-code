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
import io.kairo.api.hook.PostActingEvent;
import io.kairo.api.hook.PreCompleteEvent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextOnlyStallHookTest {

    private static PreCompleteEvent preCompleteEvent() {
        return new PreCompleteEvent(Msg.of(MsgRole.ASSISTANT, "done"), List.of(), false);
    }

    private static PostActingEvent postActingEvent(String toolName) {
        return new PostActingEvent(toolName, new ToolResult("t-1", "ok", false, Map.of()));
    }

    @Test
    void replMode_preCompletePassesThrough() {
        TextOnlyStallHook hook = new TextOnlyStallHook(true);

        HookResult<PreCompleteEvent> r = hook.onPreComplete(preCompleteEvent());
        assertThat(r.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void fileWriteCountGreaterThanZero_preCompletePassesThrough() {
        TextOnlyStallHook hook = new TextOnlyStallHook(false);

        // Simulate a write tool call
        hook.onPostActing(postActingEvent("write"));

        HookResult<PreCompleteEvent> r = hook.onPreComplete(preCompleteEvent());
        assertThat(r.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void fileWriteCountGreaterThanZero_editAlsoPassesThrough() {
        TextOnlyStallHook hook = new TextOnlyStallHook(false);

        hook.onPostActing(postActingEvent("edit"));

        HookResult<PreCompleteEvent> r = hook.onPreComplete(preCompleteEvent());
        assertThat(r.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void zeroFileWrites_preCompleteInjects() {
        TextOnlyStallHook hook = new TextOnlyStallHook(false);

        HookResult<PreCompleteEvent> r = hook.onPreComplete(preCompleteEvent());
        assertThat(r.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(r.injectedMessage()).isNotNull();
        assertThat(r.injectedMessage().text()).contains("zero file changes");
    }

    @Test
    void maxInjections_exceededStopsInjecting() {
        TextOnlyStallHook hook = new TextOnlyStallHook(false);

        // First 3 PRE_COMPLETE events → 3 injections
        for (int i = 0; i < 3; i++) {
            HookResult<PreCompleteEvent> r = hook.onPreComplete(preCompleteEvent());
            assertThat(r.decision()).isEqualTo(HookResult.Decision.INJECT);
        }

        // 4th PRE_COMPLETE → no more injections
        HookResult<PreCompleteEvent> r4 = hook.onPreComplete(preCompleteEvent());
        assertThat(r4.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void postActing_writeIncrementsFileWriteCount() {
        TextOnlyStallHook hook = new TextOnlyStallHook(false);

        hook.onPostActing(postActingEvent("write"));
        hook.onPostActing(postActingEvent("write"));

        // fileWriteCount == 2, so PRE_COMPLETE should pass through
        HookResult<PreCompleteEvent> r = hook.onPreComplete(preCompleteEvent());
        assertThat(r.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void postActing_editIncrementsFileWriteCount() {
        TextOnlyStallHook hook = new TextOnlyStallHook(false);

        hook.onPostActing(postActingEvent("edit"));

        HookResult<PreCompleteEvent> r = hook.onPreComplete(preCompleteEvent());
        assertThat(r.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void postActing_readDoesNotIncrementFileWriteCount() {
        TextOnlyStallHook hook = new TextOnlyStallHook(false);

        hook.onPostActing(postActingEvent("read"));
        hook.onPostActing(postActingEvent("read"));

        // fileWriteCount == 0, so PRE_COMPLETE should inject
        HookResult<PreCompleteEvent> r = hook.onPreComplete(preCompleteEvent());
        assertThat(r.decision()).isEqualTo(HookResult.Decision.INJECT);
    }

    @Test
    void postActing_bashDoesNotIncrementFileWriteCount() {
        TextOnlyStallHook hook = new TextOnlyStallHook(false);

        hook.onPostActing(postActingEvent("bash"));

        HookResult<PreCompleteEvent> r = hook.onPreComplete(preCompleteEvent());
        assertThat(r.decision()).isEqualTo(HookResult.Decision.INJECT);
    }
}
