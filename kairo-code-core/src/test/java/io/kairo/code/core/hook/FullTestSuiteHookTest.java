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

class FullTestSuiteHookTest {

    private static ToolResultEvent bashEvent(String content, boolean isError) {
        ToolResult result = new ToolResult("id-1", content, isError, Map.of());
        return new ToolResultEvent("bash", result, Duration.ofMillis(500), !isError);
    }

    private static ToolResultEvent nonBashEvent(String content) {
        ToolResult result = new ToolResult("id-1", content, false, Map.of());
        return new ToolResultEvent("read_file", result, Duration.ofMillis(10), true);
    }

    // --- 1. Non-bash tool: CONTINUE ---

    @Test
    void nonBashTool_doesNotTrigger() {
        FullTestSuiteHook hook = new FullTestSuiteHook();

        String output = "[INFO] Tests run: 1, Failures: 0";

        HookResult<ToolResultEvent> result = hook.onToolResult(nonBashEvent(output));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    // --- 2. Full mvn test output (multiple "Tests run:" lines): CONTINUE ---

    @Test
    void fullMvnTest_multipleTestsRunLines_continues() {
        FullTestSuiteHook hook = new FullTestSuiteHook();

        String output = """
                [INFO] Running io.kairo.code.core.RateLimiterTest
                [INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
                [INFO] Running io.kairo.code.core.ValidatorTest
                [INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
                [INFO] Running io.kairo.code.core.CacheTest
                [INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
                [INFO] BUILD SUCCESS
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, false));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    // --- 3. Single class output (one "Tests run:" line): INJECT ---

    @Test
    void singleTestClass_injects() {
        FullTestSuiteHook hook = new FullTestSuiteHook();

        String output = """
                [INFO] Running io.kairo.code.core.RateLimiterTest
                [INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
                [INFO] BUILD SUCCESS
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, false));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
        assertThat(result.injectedMessage().text()).contains("mvn test");
        assertThat(result.hookSource()).isEqualTo("FullTestSuiteHook");
    }

    // --- 4. After fired, no repeat: CONTINUE (suppressed) ---

    @Test
    void fired_doesNotRepeat() {
        FullTestSuiteHook hook = new FullTestSuiteHook();

        String output = """
                [INFO] Running io.kairo.code.core.RateLimiterTest
                [INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
                """;

        HookResult<ToolResultEvent> r1 = hook.onToolResult(bashEvent(output, false));
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);

        HookResult<ToolResultEvent> r2 = hook.onToolResult(bashEvent(output, false));
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r2.injectedMessage()).isNull();
    }

    // --- 5. Full run with BUILD SUCCESS (multiple Tests run lines): CONTINUE ---

    @Test
    void fullRunWithBuildSuccess_continues() {
        FullTestSuiteHook hook = new FullTestSuiteHook();

        String output = """
                [INFO] Running io.kairo.code.core.RateLimiterTest
                [INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
                [INFO] Running io.kairo.code.core.ValidatorTest
                [INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
                [INFO] BUILD SUCCESS
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, false));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    // --- 6. REPL mode: no trigger ---

    @Test
    void replMode_doesNotTrigger() {
        FullTestSuiteHook hook = new FullTestSuiteHook(true);

        String output = """
                [INFO] Running io.kairo.code.core.RateLimiterTest
                [INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, false));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    // --- 7. Null content: CONTINUE ---

    @Test
    void nullContent_doesNotTrigger() {
        FullTestSuiteHook hook = new FullTestSuiteHook();

        ToolResult result = new ToolResult("id-1", null, false, Map.of());
        ToolResultEvent event = new ToolResultEvent("bash", result, Duration.ofMillis(500), true);

        HookResult<ToolResultEvent> hookResult = hook.onToolResult(event);
        assertThat(hookResult.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(hookResult.injectedMessage()).isNull();
    }

    // --- 8. Zero Tests run: lines (compile only): CONTINUE ---

    @Test
    void noTestsRunLines_continues() {
        FullTestSuiteHook hook = new FullTestSuiteHook();

        String output = """
                [INFO] Compiling sources...
                [INFO] BUILD SUCCESS
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, false));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }
}
