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

class MissingTestHintHookTest {

    private static ToolResultEvent bashEvent(String content, boolean isError) {
        ToolResult result = new ToolResult("id-1", content, isError, Map.of());
        return new ToolResultEvent("bash", result, Duration.ofMillis(500), !isError);
    }

    private static ToolResultEvent nonBashEvent(String content) {
        ToolResult result = new ToolResult("id-1", content, false, Map.of());
        return new ToolResultEvent("read_file", result, Duration.ofMillis(10), true);
    }

    @Test
    void nonBashTool_doesNotTrigger() {
        MissingTestHintHook hook = new MissingTestHintHook();

        String output = "Tests run: 0, Failures: 0";

        HookResult<ToolResultEvent> result = hook.onToolResult(nonBashEvent(output));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void bashWithTestsRunZero_injects() {
        MissingTestHintHook hook = new MissingTestHintHook();

        String output = """
                [INFO] Building kairo-code-core 0.2.0-SNAPSHOT
                [INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
                [INFO] BUILD SUCCESS
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, false));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
        assertThat(result.injectedMessage().text()).containsIgnoringCase("missing");
        assertThat(result.injectedMessage().text()).contains("JUnit 5");
        assertThat(result.hookSource()).isEqualTo("MissingTestHintHook");
    }

    @Test
    void bashWithNoTestsFound_injects() {
        MissingTestHintHook hook = new MissingTestHintHook();

        String output = """
                [INFO] Building kairo-code-core 0.2.0-SNAPSHOT
                [ERROR] No tests found matching criteria
                [INFO] BUILD FAILURE
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, true));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
    }

    @Test
    void bashWithNoRunnableMethods_injects() {
        MissingTestHintHook hook = new MissingTestHintHook();

        String output = """
                [INFO] Building kairo-code-core 0.2.0-SNAPSHOT
                [WARNING] No runnable methods in test classes
                [INFO] BUILD SUCCESS
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, false));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
    }

    @Test
    void secondTrigger_doesNotInjectAgain_idempotent() {
        MissingTestHintHook hook = new MissingTestHintHook();

        String output = """
                [INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
                [INFO] BUILD SUCCESS
                """;

        HookResult<ToolResultEvent> r1 = hook.onToolResult(bashEvent(output, false));
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);

        HookResult<ToolResultEvent> r2 = hook.onToolResult(bashEvent(output, false));
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r2.injectedMessage()).isNull();
    }

    @Test
    void normalBuildSuccess_doesNotTrigger() {
        MissingTestHintHook hook = new MissingTestHintHook();

        String output = """
                [INFO] Running io.kairo.code.core.ValidatorTest
                [INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
                [INFO] BUILD SUCCESS
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, false));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void buildFailure_withTests_doesNotTrigger() {
        MissingTestHintHook hook = new MissingTestHintHook();

        String output = """
                [INFO] Running io.kairo.code.core.ValidatorTest
                [ERROR] Tests run: 5, Failures: 2, Errors: 0, Skipped: 0
                [ERROR] io.kairo.code.core.ValidatorTest.testEdgeCase -- FAILURE!
                [INFO] BUILD FAILURE
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, true));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void bashWithNullContent_doesNotTrigger() {
        MissingTestHintHook hook = new MissingTestHintHook();

        ToolResult result = new ToolResult("id-1", null, false, Map.of());
        ToolResultEvent event = new ToolResultEvent("bash", result, Duration.ofMillis(500), true);

        HookResult<ToolResultEvent> hookResult = hook.onToolResult(event);
        assertThat(hookResult.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(hookResult.injectedMessage()).isNull();
    }
}
