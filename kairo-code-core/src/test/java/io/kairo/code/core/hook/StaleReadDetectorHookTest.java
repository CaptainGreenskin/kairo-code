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

class StaleReadDetectorHookTest {

    /** Helper: create a read tool result event with metadata containing the file path. */
    private static ToolResultEvent readEvent(String path, String content) {
        ToolResult result = new ToolResult(
                "id-1", content, false, Map.of("path", path, "totalLines", 100));
        return new ToolResultEvent("read", result, Duration.ofMillis(10), true);
    }

    /** Helper: create a non-read tool result event. */
    private static ToolResultEvent nonReadEvent(String toolName, String content) {
        ToolResult result = new ToolResult("id-1", content, false, Map.of());
        return new ToolResultEvent(toolName, result, Duration.ofMillis(10), true);
    }

    // --- Test 1: non-read tool does not trigger tracking ---

    @Test
    void nonReadTool_doesNotTrack() {
        StaleReadDetectorHook hook = new StaleReadDetectorHook(3);

        // Simulate 5 bash calls — should never inject
        for (int i = 0; i < 5; i++) {
            HookResult<ToolResultEvent> result =
                    hook.onToolResult(nonReadEvent("bash", "output"));
            assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
            assertThat(result.injectedMessage()).isNull();
        }
    }

    // --- Test 2: read_file before threshold does not inject ---

    @Test
    void readBelowThreshold_doesNotInject() {
        StaleReadDetectorHook hook = new StaleReadDetectorHook(3);
        String path = "src/main/java/Foo.java";

        // First read — no injection
        HookResult<ToolResultEvent> r1 = hook.onToolResult(readEvent(path, "content1"));
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r1.injectedMessage()).isNull();

        // Second read — still no injection
        HookResult<ToolResultEvent> r2 = hook.onToolResult(readEvent(path, "content2"));
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r2.injectedMessage()).isNull();
    }

    // --- Test 3: read_file at threshold injects warning with file name and count ---

    @Test
    void readAtThreshold_injectsWarning() {
        StaleReadDetectorHook hook = new StaleReadDetectorHook(3);
        String path = "src/main/java/Foo.java";

        // First two reads — no injection
        hook.onToolResult(readEvent(path, "content1"));
        hook.onToolResult(readEvent(path, "content2"));

        // Third read — should inject
        HookResult<ToolResultEvent> r3 = hook.onToolResult(readEvent(path, "content3"));
        assertThat(r3.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(r3.injectedMessage()).isNotNull();
        assertThat(r3.injectedMessage().text()).contains("Foo.java");
        assertThat(r3.injectedMessage().text()).contains("3 times");
        assertThat(r3.injectedMessage().text())
                .contains("Avoid re-reading files you have already seen");
        assertThat(r3.hookSource()).isEqualTo("StaleReadDetectorHook");
    }

    // --- Test 4: after warning, reading same file again does not repeat injection ---

    @Test
    void readAfterWarning_noRepeatInjection() {
        StaleReadDetectorHook hook = new StaleReadDetectorHook(3);
        String path = "src/main/java/Foo.java";

        // Trigger the warning
        hook.onToolResult(readEvent(path, "content1"));
        hook.onToolResult(readEvent(path, "content2"));
        HookResult<ToolResultEvent> r3 = hook.onToolResult(readEvent(path, "content3"));
        assertThat(r3.decision()).isEqualTo(HookResult.Decision.INJECT);

        // Fourth and fifth reads — should NOT inject again
        HookResult<ToolResultEvent> r4 = hook.onToolResult(readEvent(path, "content4"));
        assertThat(r4.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r4.injectedMessage()).isNull();

        HookResult<ToolResultEvent> r5 = hook.onToolResult(readEvent(path, "content5"));
        assertThat(r5.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(r5.injectedMessage()).isNull();
    }

    // --- Test 5: different files track independently ---

    @Test
    void differentFiles_independentCounting() {
        StaleReadDetectorHook hook = new StaleReadDetectorHook(3);
        String pathA = "src/main/java/A.java";
        String pathB = "src/main/java/B.java";

        // Read A twice, B twice — no injection yet
        hook.onToolResult(readEvent(pathA, "a1"));
        hook.onToolResult(readEvent(pathB, "b1"));
        hook.onToolResult(readEvent(pathA, "a2"));
        HookResult<ToolResultEvent> rB2 = hook.onToolResult(readEvent(pathB, "b2"));
        assertThat(rB2.decision()).isEqualTo(HookResult.Decision.CONTINUE);

        // Read A a third time — should inject for A only
        HookResult<ToolResultEvent> rA3 = hook.onToolResult(readEvent(pathA, "a3"));
        assertThat(rA3.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(rA3.injectedMessage().text()).contains("A.java");

        // Read B a third time — should inject for B only
        HookResult<ToolResultEvent> rB3 = hook.onToolResult(readEvent(pathB, "b3"));
        assertThat(rB3.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(rB3.injectedMessage().text()).contains("B.java");
    }

    // --- Test 6: threshold=2 constructor works correctly ---

    @Test
    void thresholdTwo_injectsOnSecondRead() {
        StaleReadDetectorHook hook = new StaleReadDetectorHook(2);
        String path = "src/main/java/Bar.java";

        // First read — no injection
        HookResult<ToolResultEvent> r1 = hook.onToolResult(readEvent(path, "content1"));
        assertThat(r1.decision()).isEqualTo(HookResult.Decision.CONTINUE);

        // Second read — should inject
        HookResult<ToolResultEvent> r2 = hook.onToolResult(readEvent(path, "content2"));
        assertThat(r2.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(r2.injectedMessage().text()).contains("Bar.java");
        assertThat(r2.injectedMessage().text()).contains("2 times");
    }

    // --- Additional tests for extractPath and extractFileName ---

    @Test
    void extractPath_fromMetadata() {
        String path = "src/main/java/Test.java";
        ToolResult result =
                new ToolResult("id-1", "content", false, Map.of("path", path));
        ToolResultEvent event =
                new ToolResultEvent("read", result, Duration.ofMillis(10), true);

        assertThat(StaleReadDetectorHook.extractPath(event)).isEqualTo(path);
    }

    @Test
    void extractPath_fromContentFirstLine_whenNoMetadata() {
        String firstLine = "// src/main/java/Fallback.java";
        ToolResult result =
                new ToolResult("id-1", firstLine + "\nrest of content", false, Map.of());
        ToolResultEvent event =
                new ToolResultEvent("read", result, Duration.ofMillis(10), true);

        assertThat(StaleReadDetectorHook.extractPath(event)).isEqualTo(firstLine);
    }

    @Test
    void extractPath_nullWhenNoPathAvailable() {
        ToolResult result =
                new ToolResult("id-1", "", false, Map.of());
        ToolResultEvent event =
                new ToolResultEvent("read", result, Duration.ofMillis(10), true);

        assertThat(StaleReadDetectorHook.extractPath(event)).isNull();
    }

    @Test
    void extractFileName_fromFullPath() {
        assertThat(StaleReadDetectorHook.extractFileName("src/main/java/Foo.java"))
                .isEqualTo("Foo.java");
        assertThat(StaleReadDetectorHook.extractFileName("/abs/path/Bar.java"))
                .isEqualTo("Bar.java");
        assertThat(StaleReadDetectorHook.extractFileName("JustFile.txt"))
                .isEqualTo("JustFile.txt");
    }

    @Test
    void constructor_rejectsInvalidThreshold() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new StaleReadDetectorHook(0));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new StaleReadDetectorHook(-1));
    }
}
