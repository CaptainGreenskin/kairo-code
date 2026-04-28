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

class AutoCommitOnSuccessHookTest {

    private static ToolResultEvent bashEvent(String content) {
        ToolResult result = new ToolResult("id-1", content, false, Map.of());
        return new ToolResultEvent("bash", result, Duration.ofMillis(500), true);
    }

    private static ToolResultEvent editEvent(String content) {
        ToolResult result = new ToolResult("id-2", content, false, Map.of());
        return new ToolResultEvent("edit_file", result, Duration.ofMillis(10), true);
    }

    private static ToolResultEvent writeEvent(String content) {
        ToolResult result = new ToolResult("id-3", content, false, Map.of());
        return new ToolResultEvent("write_file", result, Duration.ofMillis(10), true);
    }

    private static ToolResultEvent nonBashEvent(String content) {
        ToolResult result = new ToolResult("id-4", content, false, Map.of());
        return new ToolResultEvent("read_file", result, Duration.ofMillis(10), true);
    }

    @Test
    void buildSuccessWithoutEdits_doesNotInject() {
        AutoCommitOnSuccessHook hook = new AutoCommitOnSuccessHook();

        String output = """
                [INFO] Building kairo-code-core 0.2.0-SNAPSHOT
                [INFO] Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
                [INFO] BUILD SUCCESS
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void editJavaFileAndBuildSuccess_injectsCommitPrompt() {
        AutoCommitOnSuccessHook hook = new AutoCommitOnSuccessHook();

        // First: edit a Java file
        String editOutput = "Updated src/main/java/io/kairo/code/core/Foo.java";
        HookResult<ToolResultEvent> r1 = hook.onToolResult(editEvent(editOutput));
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.CONTINUE);

        // Then: BUILD SUCCESS
        String buildOutput = """
                [INFO] Building kairo-code-core 0.2.0-SNAPSHOT
                [INFO] Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
                [INFO] BUILD SUCCESS
                """;
        HookResult<ToolResultEvent> r2 = hook.onToolResult(bashEvent(buildOutput));

        assertThat(r2.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(r2.injectedMessage()).isNotNull();
        assertThat(r2.injectedMessage().text()).contains("git add -A");
        assertThat(r2.injectedMessage().text()).contains("git commit");
        assertThat(r2.injectedMessage().text()).contains("BUILD SUCCESS");
        assertThat(r2.hookSource()).isEqualTo("AutoCommitOnSuccessHook");
    }

    @Test
    void writeJavaFileAndBuildSuccess_injectsCommitPrompt() {
        AutoCommitOnSuccessHook hook = new AutoCommitOnSuccessHook();

        String writeOutput = "Created src/main/java/io/kairo/code/core/Bar.java";
        HookResult<ToolResultEvent> r1 = hook.onToolResult(writeEvent(writeOutput));
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.CONTINUE);

        String buildOutput = "[INFO] BUILD SUCCESS";
        HookResult<ToolResultEvent> r2 = hook.onToolResult(bashEvent(buildOutput));

        assertThat(r2.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(r2.injectedMessage()).isNotNull();
        assertThat(r2.injectedMessage().text()).contains("git add -A");
    }

    @Test
    void buildSuccessSecondTrigger_doesNotInjectAgain_idempotent() {
        AutoCommitOnSuccessHook hook = new AutoCommitOnSuccessHook();

        hook.onToolResult(editEvent("Fixed Foo.java"));
        hook.onToolResult(bashEvent("[INFO] BUILD SUCCESS"));

        // Second BUILD SUCCESS should NOT inject again
        HookResult<ToolResultEvent> r2 = hook.onToolResult(bashEvent("[INFO] BUILD SUCCESS"));
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r2.injectedMessage()).isNull();
    }

    @Test
    void buildFailure_doesNotInject() {
        AutoCommitOnSuccessHook hook = new AutoCommitOnSuccessHook();

        hook.onToolResult(editEvent("Fixed Foo.java"));

        String buildOutput = """
                [ERROR] Tests run: 5, Failures: 2, Errors: 0, Skipped: 0
                [INFO] BUILD FAILURE
                """;
        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(buildOutput));

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void nonBashTool_doesNotTriggerCommitPrompt_onlySetsHasEditedFlag() {
        AutoCommitOnSuccessHook hook = new AutoCommitOnSuccessHook();

        // edit_file sets hasEdited but doesn't trigger commit
        String editOutput = "Fixed src/main/java/io/kairo/code/core/Baz.java";
        HookResult<ToolResultEvent> r1 = hook.onToolResult(editEvent(editOutput));
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r1.injectedMessage()).isNull();

        // A non-bash tool result with BUILD SUCCESS should NOT trigger
        // (this tests that only "bash" tool name triggers commit)
        String successOutput = "BUILD SUCCESS";
        ToolResult result = new ToolResult("id-5", successOutput, false, Map.of());
        ToolResultEvent fakeSuccess = new ToolResultEvent("some_tool", result, Duration.ofMillis(10), true);
        HookResult<ToolResultEvent> r2 = hook.onToolResult(fakeSuccess);

        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r2.injectedMessage()).isNull();
    }

    @Test
    void editNonJavaFileAndBuildSuccess_doesNotInject() {
        AutoCommitOnSuccessHook hook = new AutoCommitOnSuccessHook();

        // Edit a non-Java file (e.g., pom.xml)
        String editOutput = "Updated pom.xml";
        HookResult<ToolResultEvent> r1 = hook.onToolResult(editEvent(editOutput));
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.CONTINUE);

        String buildOutput = "[INFO] BUILD SUCCESS";
        HookResult<ToolResultEvent> r2 = hook.onToolResult(bashEvent(buildOutput));

        // Should NOT inject because no .java file was edited
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r2.injectedMessage()).isNull();
    }
}
