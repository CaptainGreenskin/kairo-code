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

import io.kairo.api.agent.AgentState;
import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.SessionEndEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionResultWriterHookTest {

    private static SessionEndEvent completedEvent() {
        return new SessionEndEvent(
                "kairo-code", AgentState.COMPLETED, 12, 45200, Duration.ofSeconds(187), null);
    }

    private static SessionEndEvent failedEvent() {
        return new SessionEndEvent(
                "kairo-code", AgentState.FAILED, 3, 1200, Duration.ofSeconds(30), "OOM: heap space");
    }

    @Test
    void nullWorkingDir_doesNotWriteFile_noException() {
        SessionResultWriterHook hook = new SessionResultWriterHook(null);

        HookResult<SessionEndEvent> result = hook.onSessionEnd(completedEvent());

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }

    @Test
    void completedSession_writesKairosessionResultJson(@TempDir Path tempDir) throws Exception {
        SessionResultWriterHook hook = new SessionResultWriterHook(tempDir);

        hook.onSessionEnd(completedEvent());

        Path resultFile = tempDir.resolve("KAIRO_SESSION_RESULT.json");
        assertThat(resultFile).exists();

        String content = Files.readString(resultFile);
        assertThat(content).contains("\"finalState\": \"COMPLETED\"");
        assertThat(content).contains("\"iterations\": 12");
        assertThat(content).contains("\"tokensUsed\": 45200");
        assertThat(content).contains("\"durationSeconds\": 187");
        assertThat(content).contains("\"error\": null");
    }

    @Test
    void failedSession_writesFileWithErrorField(@TempDir Path tempDir) throws Exception {
        SessionResultWriterHook hook = new SessionResultWriterHook(tempDir);

        hook.onSessionEnd(failedEvent());

        Path resultFile = tempDir.resolve("KAIRO_SESSION_RESULT.json");
        assertThat(resultFile).exists();

        String content = Files.readString(resultFile);
        assertThat(content).contains("\"finalState\": \"FAILED\"");
        assertThat(content).contains("\"error\": \"OOM: heap space\"");
    }

    @Test
    void outputFile_containsAllRequiredFields_andIsValidJson(@TempDir Path tempDir) throws Exception {
        SessionResultWriterHook hook = new SessionResultWriterHook(tempDir);

        hook.onSessionEnd(completedEvent());

        String content = Files.readString(tempDir.resolve("KAIRO_SESSION_RESULT.json"));

        // All required fields present
        assertThat(content).contains("finalState");
        assertThat(content).contains("iterations");
        assertThat(content).contains("tokensUsed");
        assertThat(content).contains("durationSeconds");
        assertThat(content).contains("error");
        assertThat(content).contains("timestamp");

        // Basic JSON structure: starts with { and ends with }
        assertThat(content.trim()).startsWith("{").endsWith("}");
    }

    @Test
    void nonexistentWorkingDir_silentlyIgnored() {
        Path nonexistent = Path.of("/nonexistent/path/that/does/not/exist");
        SessionResultWriterHook hook = new SessionResultWriterHook(nonexistent);

        HookResult<SessionEndEvent> result = hook.onSessionEnd(completedEvent());

        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
    }
}
