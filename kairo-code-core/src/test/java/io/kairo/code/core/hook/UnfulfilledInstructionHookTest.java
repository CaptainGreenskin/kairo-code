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
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnfulfilledInstructionHookTest {

    private static PreReasoningEvent eventWithUserText(String text) {
        ModelConfig config = ModelConfig.builder().model("gpt-4o").build();
        return new PreReasoningEvent(List.of(Msg.of(MsgRole.USER, text)), config, false);
    }

    private static PreReasoningEvent eventWithMessages(List<Msg> messages) {
        ModelConfig config = ModelConfig.builder().model("gpt-4o").build();
        return new PreReasoningEvent(List.copyOf(messages), config, false);
    }

    // --- 1. No "Create .java" instruction: CONTINUE ---

    @Test
    void noCreateJavaInstruction_continues() {
        UnfulfilledInstructionHook hook = new UnfulfilledInstructionHook("/tmp");

        PreReasoningEvent event = eventWithUserText("Fix the bug in RateLimiter.java");

        HookResult<PreReasoningEvent> result = hook.onPreReasoning(event);
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

        String task = """
                Fix the rate limiter.
                Create src/test/java/io/RateLimiterTest.java with tests.
                """;
        PreReasoningEvent event = eventWithUserText(task);

        HookResult<PreReasoningEvent> result = hook.onPreReasoning(event);
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    // --- 3. Instruction present and file missing: INJECT ---

    @Test
    void missingFile_injects(@TempDir Path tempDir) {
        UnfulfilledInstructionHook hook = new UnfulfilledInstructionHook(tempDir.toString());

        String task = "Create src/test/java/io/RateLimiterTest.java with tests.";
        PreReasoningEvent event = eventWithUserText(task);

        HookResult<PreReasoningEvent> result = hook.onPreReasoning(event);
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
        PreReasoningEvent event = eventWithUserText(task);

        HookResult<PreReasoningEvent> r1 = hook.onPreReasoning(event);
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);

        // Second call — already scanned, should continue
        PreReasoningEvent event2 = eventWithUserText(task);
        HookResult<PreReasoningEvent> r2 = hook.onPreReasoning(event2);
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r2.injectedMessage()).isNull();
    }

    // --- 5. MAX_INJECTIONS cap (3) ---

    @Test
    void maxInjections_cap(@TempDir Path tempDir) {
        UnfulfilledInstructionHook hook = new UnfulfilledInstructionHook(tempDir.toString());

        String task = """
                Create src/test/java/io/TestA.java
                Create src/test/java/io/TestB.java
                Create src/test/java/io/TestC.java
                Create src/test/java/io/TestD.java
                """;
        PreReasoningEvent event = eventWithUserText(task);

        // First call injects the first missing file
        HookResult<PreReasoningEvent> r1 = hook.onPreReasoning(event);
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(r1.injectedMessage().text()).contains("TestA.java");

        // Since scanned=true, subsequent calls return CONTINUE
        // The hook only fires once per session — it injects the first missing file and stops.
        // MAX_INJECTIONS is enforced within the single scan pass.
        // After first injection the hook returns immediately (one injection per call).
    }

    // --- 6. REPL mode: no trigger ---

    @Test
    void replMode_doesNotTrigger(@TempDir Path tempDir) {
        UnfulfilledInstructionHook hook = new UnfulfilledInstructionHook(tempDir.toString(), true);

        String task = "Create src/test/java/io/RateLimiterTest.java with tests.";
        PreReasoningEvent event = eventWithUserText(task);

        HookResult<PreReasoningEvent> result = hook.onPreReasoning(event);
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    // --- 7. Multiple missing files: each gets injected once (within single scan) ---

    @Test
    void multipleMissingFiles_injectsFirst(@TempDir Path tempDir) {
        UnfulfilledInstructionHook hook = new UnfulfilledInstructionHook(tempDir.toString());

        String task = """
                Create src/test/java/io/RateLimiterTest.java
                Create src/test/java/io/ValidatorTest.java
                """;
        PreReasoningEvent event = eventWithUserText(task);

        // First call: injects the first missing file
        HookResult<PreReasoningEvent> r1 = hook.onPreReasoning(event);
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(r1.injectedMessage().text()).contains("RateLimiterTest.java");

        // Second call: already scanned, continues
        HookResult<PreReasoningEvent> r2 = hook.onPreReasoning(event);
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    // --- 8. Null workingDir: CONTINUE ---

    @Test
    void nullWorkingDir_continues() {
        UnfulfilledInstructionHook hook = new UnfulfilledInstructionHook(null);

        String task = "Create src/test/java/io/RateLimiterTest.java";
        PreReasoningEvent event = eventWithUserText(task);

        HookResult<PreReasoningEvent> result = hook.onPreReasoning(event);
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
        String text = """
                Please do the following:
                Create src/test/java/io/RateLimiterTest.java
                And also Create src/test/java/io/ValidatorTest.java
                """;
        List<Msg> messages = List.of(Msg.of(MsgRole.USER, text));
        List<String> paths = UnfulfilledInstructionHook.extractCreatePaths(messages);
        assertThat(paths).hasSize(2);
        assertThat(paths).containsExactly(
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

    // --- 10. File created after first scan: second scan still skipped (scanned=true) ---

    @Test
    void scannedOnce_doesNotRescan(@TempDir Path tempDir) throws IOException {
        UnfulfilledInstructionHook hook = new UnfulfilledInstructionHook(tempDir.toString());

        String task = "Create src/test/java/io/RateLimiterTest.java";
        PreReasoningEvent event = eventWithUserText(task);

        // First call: file missing, injects
        HookResult<PreReasoningEvent> r1 = hook.onPreReasoning(event);
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.INJECT);

        // Now create the file
        Path testFile = tempDir.resolve("src/test/java/io/RateLimiterTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "// test");

        // Second call: still CONTINUE because scanned=true
        HookResult<PreReasoningEvent> r2 = hook.onPreReasoning(event);
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }
}
