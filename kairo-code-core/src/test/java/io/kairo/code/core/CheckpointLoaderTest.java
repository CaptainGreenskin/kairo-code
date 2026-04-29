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
package io.kairo.code.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckpointLoaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void fileNotFound_returnsEmpty() {
        Optional<CheckpointData> result = CheckpointLoader.load(Path.of("/nonexistent"), MAPPER);
        assertThat(result).isEmpty();
    }

    @Test
    void nullWorkingDir_returnsEmpty(@TempDir Path tempDir) {
        Optional<CheckpointData> result = CheckpointLoader.load(null, MAPPER);
        assertThat(result).isEmpty();
    }

    @Test
    void normalFile_returnsCheckpointData(@TempDir Path tempDir) throws Exception {
        // Write a valid checkpoint file
        Map<String, Object> checkpoint = Map.of(
                "sessionId", "session-123",
                "iteration", 5,
                "timestamp", "2026-04-29T10:05:00Z",
                "messages", List.of(
                        Map.of("role", "user", "content", "Fix the bug"),
                        Map.of("role", "assistant", "content", "I'll fix it")
                )
        );

        Path sessionDir = tempDir.resolve(".kairo-session");
        Files.createDirectories(sessionDir);
        MAPPER.writeValue(sessionDir.resolve("checkpoint.json").toFile(), checkpoint);

        Optional<CheckpointData> result = CheckpointLoader.load(tempDir, MAPPER);
        assertThat(result).isPresent();

        CheckpointData data = result.get();
        assertThat(data.sessionId()).isEqualTo("session-123");
        assertThat(data.iteration()).isEqualTo(5);
        assertThat(data.messages()).hasSize(2);
        assertThat(data.messages().get(0).role()).isEqualTo(MsgRole.USER);
        assertThat(data.messages().get(0).text()).isEqualTo("Fix the bug");
        assertThat(data.messages().get(1).role()).isEqualTo(MsgRole.ASSISTANT);
        assertThat(data.messages().get(1).text()).isEqualTo("I'll fix it");
    }

    @Test
    void malformedJson_returnsEmpty(@TempDir Path tempDir) throws Exception {
        Path sessionDir = tempDir.resolve(".kairo-session");
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("checkpoint.json"), "not valid json {{{");

        Optional<CheckpointData> result = CheckpointLoader.load(tempDir, MAPPER);
        assertThat(result).isEmpty();
    }

    @Test
    void missingMessagesField_returnsEmpty(@TempDir Path tempDir) throws Exception {
        Map<String, Object> checkpoint = Map.of(
                "sessionId", "session-123",
                "iteration", 3
        );

        Path sessionDir = tempDir.resolve(".kairo-session");
        Files.createDirectories(sessionDir);
        MAPPER.writeValue(sessionDir.resolve("checkpoint.json").toFile(), checkpoint);

        Optional<CheckpointData> result = CheckpointLoader.load(tempDir, MAPPER);
        assertThat(result).isEmpty();
    }

    @Test
    void withToolCalls_parsesCorrectly(@TempDir Path tempDir) throws Exception {
        Map<String, Object> assistantMsg = Map.of(
                "role", "assistant",
                "content", "Let me edit",
                "toolCalls", List.of(
                        Map.of("id", "t1", "name", "edit", "input", Map.of("path", "Foo.java"))
                )
        );

        Map<String, Object> checkpoint = Map.of(
                "sessionId", "session-456",
                "iteration", 2,
                "timestamp", "2026-04-29T11:00:00Z",
                "messages", List.of(
                        Map.of("role", "user", "content", "Fix Foo.java"),
                        assistantMsg
                )
        );

        Path sessionDir = tempDir.resolve(".kairo-session");
        Files.createDirectories(sessionDir);
        MAPPER.writeValue(sessionDir.resolve("checkpoint.json").toFile(), checkpoint);

        Optional<CheckpointData> result = CheckpointLoader.load(tempDir, MAPPER);
        assertThat(result).isPresent();
        assertThat(result.get().messages()).hasSize(2);

        List<Msg> msgs = result.get().messages();
        assertThat(msgs.get(1).role()).isEqualTo(MsgRole.ASSISTANT);
        assertThat(msgs.get(1).text()).isEqualTo("Let me edit");
    }
}
