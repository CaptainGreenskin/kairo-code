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

class TestFailureFeedbackHookTest {

    private static ToolResultEvent bashEvent(String content, boolean isError) {
        ToolResult result = new ToolResult("id-1", content, isError, Map.of());
        return new ToolResultEvent("bash", result, Duration.ofMillis(500), !isError);
    }

    private static ToolResultEvent nonBashEvent(String content) {
        ToolResult result = new ToolResult("id-1", content, false, Map.of());
        return new ToolResultEvent("read_file", result, Duration.ofMillis(10), true);
    }

    // --- Extraction tests ---

    @Test
    void bashWithBuildSuccess_doesNotInject() {
        TestFailureFeedbackHook hook = new TestFailureFeedbackHook();

        String output = """
                [INFO] Building kairo-code-core 0.2.0-SNAPSHOT
                [INFO] Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
                [INFO] BUILD SUCCESS
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, false));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void nonBashToolWithBuildFailure_doesNotInject() {
        TestFailureFeedbackHook hook = new TestFailureFeedbackHook();

        String output = """
                [ERROR] BUILD FAILURE
                [ERROR] some error
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(nonBashEvent(output));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void bashWithBuildFailure_injectsStructuredMessage() {
        TestFailureFeedbackHook hook = new TestFailureFeedbackHook();

        String output = """
                [INFO] Running io.kairo.code.core.CacheTest
                [ERROR] Tests run: 5, Failures: 2, Errors: 0, Skipped: 0
                [ERROR] io.kairo.code.core.CacheTest.sizeAfterExpiry -- Time elapsed: 0.012 s <<< FAILURE!
                [ERROR] io.kairo.code.core.CacheTest.evictionPolicy -- Time elapsed: 0.003 s <<< FAILURE!
                [INFO] BUILD FAILURE
                [INFO] Total time:  3.214 s
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, true));

        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
        assertThat(result.injectedMessage().text()).contains("Test failures detected");
        assertThat(result.injectedMessage().text()).contains("sizeAfterExpiry");
        assertThat(result.injectedMessage().text()).contains("evictionPolicy");
        assertThat(result.injectedMessage().text()).contains("mvn test");
        assertThat(result.hookSource()).isEqualTo("TestFailureFeedbackHook");
    }

    @Test
    void extractErrorSummary_capturesErrorLines() {
        String output = """
                [INFO] Some info line
                [ERROR] Tests run: 5, Failures: 2, Errors: 0, Skipped: 0
                [ERROR] io.kairo.code.core.CacheTest.sizeAfterExpiry -- FAILURE!
                [ERROR] io.kairo.code.core.CacheTest.evictionPolicy -- FAILURE!
                [INFO] BUILD FAILURE
                [WARNING] some warning
                """;

        String summary = TestFailureFeedbackHook.extractErrorSummary(output);

        assertThat(summary).contains("[ERROR] Tests run: 5, Failures: 2");
        assertThat(summary).contains("sizeAfterExpiry");
        assertThat(summary).contains("evictionPolicy");
        // Non-ERROR lines should not be included
        assertThat(summary).doesNotContain("[INFO]");
        assertThat(summary).doesNotContain("[WARNING]");
    }

    @Test
    void extractErrorSummary_capsAtMaxLines() {
        StringBuilder sb = new StringBuilder();
        sb.append("[INFO] BUILD FAILURE\n");
        for (int i = 0; i < 30; i++) {
            sb.append("[ERROR] Error line ").append(i).append("\n");
        }
        String output = sb.toString();

        String summary = TestFailureFeedbackHook.extractErrorSummary(output);
        long errorLineCount = summary.lines().count();

        assertThat(errorLineCount).isLessThanOrEqualTo(20);
    }

    @Test
    void sameSummary_doesNotInjectTwice_idempotent() {
        TestFailureFeedbackHook hook = new TestFailureFeedbackHook();

        String output = """
                [ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
                [ERROR] io.kairo.code.core.FooTest.testBar -- FAILURE!
                [INFO] BUILD FAILURE
                """;

        // First trigger — should inject
        HookResult<ToolResultEvent> r1 = hook.onToolResult(bashEvent(output, true));
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);

        // Same output again — should NOT inject (idempotent)
        HookResult<ToolResultEvent> r2 = hook.onToolResult(bashEvent(output, true));
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r2.injectedMessage()).isNull();
    }

    @Test
    void differentSummary_injectsAgain() {
        TestFailureFeedbackHook hook = new TestFailureFeedbackHook();

        String output1 = """
                [ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
                [ERROR] io.kairo.code.core.FooTest.testBar -- FAILURE!
                [INFO] BUILD FAILURE
                """;

        String output2 = """
                [ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
                [ERROR] io.kairo.code.core.BazTest.testQux -- FAILURE!
                [INFO] BUILD FAILURE
                """;

        HookResult<ToolResultEvent> r1 = hook.onToolResult(bashEvent(output1, true));
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);

        // Different failure — should inject again
        HookResult<ToolResultEvent> r2 = hook.onToolResult(bashEvent(output2, true));
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.INJECT);
    }

    @Test
    void exceedsMaxInjections_stopsInjecting() {
        TestFailureFeedbackHook hook = new TestFailureFeedbackHook(3);

        for (int i = 0; i < 5; i++) {
            String output = """
                    [ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
                    [ERROR] io.kairo.code.core.Test%d.test -- FAILURE!
                    [INFO] BUILD FAILURE
                    """.formatted(i);

            HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, true));

            if (i < 3) {
                assertThat(result.decision())
                        .as("Injection %d should succeed (within max)", i)
                        .isEqualTo(HookResult.Decision.INJECT);
            } else {
                assertThat(result.decision())
                        .as("Injection %d should be blocked (max reached)", i)
                        .isEqualTo(HookResult.Decision.CONTINUE);
                assertThat(result.injectedMessage()).isNull();
            }
        }
    }

    @Test
    void countFailures_parsesFailureCount() {
        String output = """
                [ERROR] Tests run: 5, Failures: 2, Errors: 1, Skipped: 0
                [ERROR] io.kairo.code.core.CacheTest.sizeAfterExpiry -- FAILURE!
                [ERROR] io.kairo.code.core.CacheTest.evictionPolicy -- FAILURE!
                [INFO] BUILD FAILURE
                """;

        int count = TestFailureFeedbackHook.countFailures(output);
        assertThat(count).isEqualTo(3); // 2 Failures + 1 Error
    }

    @Test
    void countFailures_fallbackToOne_whenUnparseable() {
        String output = """
                [ERROR] Something went wrong in test execution
                [ERROR] io.kairo.code.core.Test.assertSomething failed
                [INFO] BUILD FAILURE
                """;

        int count = TestFailureFeedbackHook.countFailures(output);
        assertThat(count).isGreaterThanOrEqualTo(1);
    }
}
