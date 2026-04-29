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
import io.kairo.api.hook.PreCompleteEvent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnfulfilledInstructionHookTest {

    private static PreCompleteEvent eventWithUserText(String text) {
        Msg assistant = Msg.of(MsgRole.ASSISTANT, "I have completed the task.");
        return new PreCompleteEvent(assistant, List.of(Msg.of(MsgRole.USER, text)), false);
    }

    private static PreCompleteEvent eventWithMessages(List<Msg> messages) {
        Msg assistant = Msg.of(MsgRole.ASSISTANT, "I have completed the task.");
        return new PreCompleteEvent(assistant, List.copyOf(messages), false);
    }

    // --- 1. No "Create .java" instruction: CONTINUE ---

    @Test
    void noCreateJavaInstruction_continues() {
        UnfulfilledInstructionHook hook = new UnfulfilledInstructionHook("/tmp");

        PreCompleteEvent event = eventWithUserText("Fix the bug in RateLimiter.java");

        HookResult<PreCompleteEvent> result = hook.onPreComplete(event);
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    // --- 2. Instruction present and file exists: CONTINUE ---

    @Test
    void fileAlreadyExists_continues(@TempDir Path tempDir) throws IOException {
        Path testFile = tempDir.resolve("src/test/java/io/RateLimiterTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "// test");

        UnfulfilledInstructionHook hook = new UnfulfilledInstructionHook(tempDir.toString());

        String task =
                """
                Fix the rate limiter.
                Create src/test/java/io/RateLimiterTest.java with tests.
                """;
        PreCompleteEvent event = eventWithUserText(task);

        HookResult<PreCompleteEvent> result = hook.onPreComplete(event);
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    // --- 3. Instruction present and file missing: INJECT ---

    @Test
    void missingFile_injects(@TempDir Path tempDir) {
        UnfulfilledInstructionHook hook = new UnfulfilledInstructionHook(tempDir.toString());

        String task = "Create src/test/java/io/RateLimiterTest.java with tests.";
        PreCompleteEvent event = eventWithUserText(task);

        HookResult<PreCompleteEvent> result = hook.onPreComplete(event);
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
        assertThat(result.injectedMessage().text()).contains("RateLimiterTest.java");
        assertThat(result.hookSource()).isEqualTo("UnfulfilledInstructionHook");
    }

    // --- 4. Same file does not re-inject (dedup via injectedFiles) ---

    @Test
    void sameFile_doesNotReinject(@TempDir Path tempDir) {
        UnfulfilledInstructionHook hook = new UnfulfilledInstructionHook(tempDir.toString());

        String task = "Create src/test/java/io/RateLimiterTest.java with tests.";
        PreCompleteEvent event = eventWithUserText(task);

        HookResult<PreCompleteEvent> r1 = hook.onPreComplete(event);
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);

        // Second call — same file already in injectedFiles, should not re-inject
        PreCompleteEvent event2 = eventWithUserText(task);
        HookResult<PreCompleteEvent> r2 = hook.onPreComplete(event2);
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r2.injectedMessage()).isNull();
    }

    // --- 5. MAX_INJECTIONS cap (3) ---

    @Test
    void maxInjections_cap(@TempDir Path tempDir) {
        UnfulfilledInstructionHook hook = new UnfulfilledInstructionHook(tempDir.toString());

        // First call injects the first missing file (TestA)
        String taskA = "Create src/test/java/io/TestA.java";
        HookResult<PreCompleteEvent> r1 = hook.onPreComplete(eventWithUserText(taskA));
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(r1.injectedMessage().text()).contains("TestA.java");

        // Second call (different file) injects TestB
        String taskB = "Create src/test/java/io/TestB.java";
        HookResult<PreCompleteEvent> r2 = hook.onPreComplete(eventWithUserText(taskB));
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.INJECT);

        // Third call injects TestC
        String taskC = "Create src/test/java/io/TestC.java";
        HookResult<PreCompleteEvent> r3 = hook.onPreComplete(eventWithUserText(taskC));
        assertThat(r3.decision()).isEqualTo(HookResult.Decision.INJECT);

        // Fourth call: injection count == MAX_INJECTIONS (3), so CONTINUE
        String taskD = "Create src/test/java/io/TestD.java";
        HookResult<PreCompleteEvent> r4 = hook.onPreComplete(eventWithUserText(taskD));
        assertThat(r4.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    // --- 6. REPL mode: no trigger ---

    @Test
    void replMode_doesNotTrigger(@TempDir Path tempDir) {
        UnfulfilledInstructionHook hook = new UnfulfilledInstructionHook(tempDir.toString(), true);

        String task = "Create src/test/java/io/RateLimiterTest.java with tests.";
        PreCompleteEvent event = eventWithUserText(task);

        HookResult<PreCompleteEvent> result = hook.onPreComplete(event);
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    // --- 7. Multiple missing files: injects first missing, then second on next call ---

    @Test
    void multipleMissingFiles_injectsFirstThenSecond(@TempDir Path tempDir) {
        UnfulfilledInstructionHook hook = new UnfulfilledInstructionHook(tempDir.toString());

        String task =
                """
                Create src/test/java/io/RateLimiterTest.java
                Create src/test/java/io/ValidatorTest.java
                """;

        // First call: injects the first missing file (RateLimiterTest.java)
        HookResult<PreCompleteEvent> r1 = hook.onPreComplete(eventWithUserText(task));
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(r1.injectedMessage().text()).contains("RateLimiterTest.java");

        // Second call: RateLimiterTest.java already in injectedFiles, injects ValidatorTest.java
        HookResult<PreCompleteEvent> r2 = hook.onPreComplete(eventWithUserText(task));
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(r2.injectedMessage().text()).contains("ValidatorTest.java");

        // Third call: both files in injectedFiles → CONTINUE
        HookResult<PreCompleteEvent> r3 = hook.onPreComplete(eventWithUserText(task));
        assertThat(r3.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    // --- 8. Null workingDir: CONTINUE ---

    @Test
    void nullWorkingDir_continues() {
        UnfulfilledInstructionHook hook = new UnfulfilledInstructionHook(null);

        String task = "Create src/test/java/io/RateLimiterTest.java";
        PreCompleteEvent event = eventWithUserText(task);

        HookResult<PreCompleteEvent> result = hook.onPreComplete(event);
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    // --- 9. extractCreatePaths static method tests ---

    @Test
    void extractCreatePaths_noUserMessage_returnsEmpty() {
        List<Msg> messages = List.of(Msg.of(MsgRole.ASSISTANT, "I will create the test."));
        List<String> paths = UnfulfilledInstructionHook.extractCreatePaths(messages);
        assertThat(paths).isEmpty();
    }

    @Test
    void extractCreatePaths_matchesMultiplePaths() {
        String text =
                """
                Please do the following:
                Create src/test/java/io/RateLimiterTest.java
                And also Create src/test/java/io/ValidatorTest.java
                """;
        List<Msg> messages = List.of(Msg.of(MsgRole.USER, text));
        List<String> paths = UnfulfilledInstructionHook.extractCreatePaths(messages);
        assertThat(paths).hasSize(2);
        assertThat(paths)
                .containsExactly(
                        "src/test/java/io/RateLimiterTest.java",
                        "src/test/java/io/ValidatorTest.java");
    }

    @Test
    void extractCreatePaths_ignoresNonTestPaths() {
        // Only src/test/ paths are matched by the regex
        String text = "Create src/main/java/io/RateLimiter.java";
        List<Msg> messages = List.of(Msg.of(MsgRole.USER, text));
        List<String> paths = UnfulfilledInstructionHook.extractCreatePaths(messages);
        assertThat(paths).isEmpty();
    }

    @Test
    void extractCreatePaths_matchesBacktickWrappedPath() {
        String text = "Create `src/test/java/io/RateLimiterTest.java` with tests.";
        List<Msg> messages = List.of(Msg.of(MsgRole.USER, text));
        List<String> paths = UnfulfilledInstructionHook.extractCreatePaths(messages);
        assertThat(paths).containsExactly("src/test/java/io/RateLimiterTest.java");
    }

    // --- 10. File created between calls: second scan finds it exists ---

    @Test
    void fileCreatedBetweenCalls_secondCallContinues(@TempDir Path tempDir) throws IOException {
        UnfulfilledInstructionHook hook = new UnfulfilledInstructionHook(tempDir.toString());

        String task = "Create src/test/java/io/RateLimiterTest.java";

        // First call: file missing → INJECT, adds to injectedFiles
        HookResult<PreCompleteEvent> r1 = hook.onPreComplete(eventWithUserText(task));
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);

        // File gets created by the agent
        Path testFile = tempDir.resolve("src/test/java/io/RateLimiterTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "// test");

        // Second call: file now exists, already in injectedFiles → CONTINUE
        HookResult<PreCompleteEvent> r2 = hook.onPreComplete(eventWithUserText(task));
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }
}
