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
import io.kairo.api.hook.ToolResultEvent;
import io.kairo.api.tool.ToolResult;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolBudgetHookTest {

    private static ToolResultEvent toolEvent(String tool) {
        ToolResult result = ToolResult.success("id1", "output");
        return new ToolResultEvent(tool, result, Duration.ofMillis(50), true);
    }

    @Test
    void belowWarnThreshold_continues() {
        ToolBudgetHook hook = new ToolBudgetHook(false, 60, 100);

        // 58 loop calls + 1 final call = 59 total, below warn threshold of 60
        for (int i = 0; i < 58; i++) {
            hook.onToolResult(toolEvent("bash"));
        }

        HookResult<ToolResultEvent> result = hook.onToolResult(toolEvent("bash"));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void atWarnThreshold_injectsWarnMessage() {
        ToolBudgetHook hook = new ToolBudgetHook(false, 60, 100);

        for (int i = 0; i < 59; i++) {
            hook.onToolResult(toolEvent("bash"));
        }

        // 60th call — should trigger warn
        HookResult<ToolResultEvent> result = hook.onToolResult(toolEvent("bash"));

        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
        assertThat(result.injectedMessage().text()).contains("tool calls");
        assertThat(result.injectedMessage().text()).contains("60");
        assertThat(result.hookSource()).isEqualTo("ToolBudgetHook");
    }

    @Test
    void warnAlreadyFired_noRepeatInjection() {
        ToolBudgetHook hook = new ToolBudgetHook(false, 60, 100);

        // Reach warn threshold
        for (int i = 0; i < 59; i++) {
            hook.onToolResult(toolEvent("bash"));
        }
        assertThat(hook.onToolResult(toolEvent("bash")).decision())
                .isEqualTo(HookResult.Decision.INJECT);

        // Next call — should NOT inject again
        HookResult<ToolResultEvent> result = hook.onToolResult(toolEvent("bash"));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void atForceThreshold_injectsForceMessage() {
        ToolBudgetHook hook = new ToolBudgetHook(false, 60, 100);

        for (int i = 0; i < 99; i++) {
            hook.onToolResult(toolEvent("bash"));
        }

        // 100th call — should trigger force
        HookResult<ToolResultEvent> result = hook.onToolResult(toolEvent("bash"));

        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
        assertThat(result.injectedMessage().text()).contains("STOP");
        assertThat(result.injectedMessage().text()).contains("100");
        assertThat(result.hookSource()).isEqualTo("ToolBudgetHook");
    }

    @Test
    void forceAlreadyFired_noRepeatInjection() {
        ToolBudgetHook hook = new ToolBudgetHook(false, 60, 100);

        // Reach force threshold
        for (int i = 0; i < 99; i++) {
            hook.onToolResult(toolEvent("bash"));
        }
        assertThat(hook.onToolResult(toolEvent("bash")).decision())
                .isEqualTo(HookResult.Decision.INJECT);

        // Next call — should NOT inject again
        HookResult<ToolResultEvent> result = hook.onToolResult(toolEvent("bash"));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void replMode_noInjectionAtAnyThreshold() {
        ToolBudgetHook hook = new ToolBudgetHook(true, 60, 100);

        // Simulate 200 tool calls in REPL mode
        for (int i = 0; i < 200; i++) {
            HookResult<ToolResultEvent> result = hook.onToolResult(toolEvent("bash"));
            assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
            assertThat(result.injectedMessage()).isNull();
        }
    }

    @Test
    void warnFiredBeforeForce_forceStillFiresAtThreshold() {
        ToolBudgetHook hook = new ToolBudgetHook(false, 60, 100);

        // Reach and fire warn
        for (int i = 0; i < 59; i++) {
            hook.onToolResult(toolEvent("bash"));
        }
        assertThat(hook.onToolResult(toolEvent("bash")).decision())
                .isEqualTo(HookResult.Decision.INJECT);

        // Continue to force threshold
        for (int i = 60; i < 99; i++) {
            hook.onToolResult(toolEvent("bash"));
        }

        // 100th call — should trigger force
        HookResult<ToolResultEvent> result = hook.onToolResult(toolEvent("bash"));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage().text()).contains("STOP");
    }

    @Test
    void zeroWarnThreshold_onlyForceFires() {
        ToolBudgetHook hook = new ToolBudgetHook(false, 0, 50);

        // 49 calls — no injection
        for (int i = 0; i < 49; i++) {
            assertThat(hook.onToolResult(toolEvent("bash")).decision())
                    .isEqualTo(HookResult.Decision.CONTINUE);
        }

        // 50th call — force fires
        HookResult<ToolResultEvent> result = hook.onToolResult(toolEvent("bash"));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage().text()).contains("STOP");
    }

    @Test
    void defaultConstructor_usesEnvDefaults() {
        // When env vars are not set, defaults are warn=60, force=100
        ToolBudgetHook hook = new ToolBudgetHook();

        // Below warn: no injection
        for (int i = 0; i < 59; i++) {
            assertThat(hook.onToolResult(toolEvent("bash")).decision())
                    .isEqualTo(HookResult.Decision.CONTINUE);
        }

        // At warn: injects
        HookResult<ToolResultEvent> result = hook.onToolResult(toolEvent("bash"));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
    }
}
