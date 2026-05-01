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
package io.kairo.code.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.controller.TraceController;
import io.kairo.code.server.controller.TraceController.TraceEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceControllerTest {

    @TempDir
    Path tempDir;

    private TraceController controller;

    @BeforeEach
    void setUp() {
        ServerProperties props = new ServerProperties(
                "openai", "gpt-4o", tempDir.toString(),
                "https://api.openai.com", "sk-test");
        controller = new TraceController(props, new ObjectMapper());
    }

    @Test
    void getTrace_noTraceDir_returnsEmptyList() throws IOException {
        ResponseEntity<List<TraceEntry>> response = controller.getTrace("any-session");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void getTrace_emptyTraceDir_returnsEmptyList() throws IOException {
        Files.createDirectories(tempDir.resolve(".kairo-trace"));
        ResponseEntity<List<TraceEntry>> response = controller.getTrace("any-session");
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void getTrace_validJsonl_returnsEntries() throws IOException {
        Path traceDir = Files.createDirectories(tempDir.resolve(".kairo-trace"));
        String jsonl =
                "{\"phase\":\"POST_ACTING\",\"tool\":\"bash\",\"duration_ms\":123,\"status\":\"success\",\"ts\":\"2025-01-01T00:00:00Z\"}\n" +
                "{\"phase\":\"POST_ACTING\",\"tool\":\"read\",\"duration_ms\":45,\"status\":\"success\",\"ts\":\"2025-01-01T00:00:01Z\"}\n";
        Files.writeString(traceDir.resolve("session-1000.jsonl"), jsonl);

        ResponseEntity<List<TraceEntry>> response = controller.getTrace("any-session");
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody().get(0).toolName()).isEqualTo("bash");
        assertThat(response.getBody().get(0).durationMs()).isEqualTo(123);
        assertThat(response.getBody().get(1).toolName()).isEqualTo("read");
    }

    @Test
    void getTrace_malformedLine_skipped() throws IOException {
        Path traceDir = Files.createDirectories(tempDir.resolve(".kairo-trace"));
        String jsonl =
                "{\"phase\":\"POST_ACTING\",\"tool\":\"bash\",\"duration_ms\":50,\"status\":\"ok\",\"ts\":\"t1\"}\n" +
                "NOT_JSON\n" +
                "{\"phase\":\"POST_ACTING\",\"tool\":\"write\",\"duration_ms\":10,\"status\":\"ok\",\"ts\":\"t2\"}\n";
        Files.writeString(traceDir.resolve("session-2000.jsonl"), jsonl);

        ResponseEntity<List<TraceEntry>> response = controller.getTrace("any-session");
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void getTrace_multipleFiles_returnsLatest() throws IOException, InterruptedException {
        Path traceDir = Files.createDirectories(tempDir.resolve(".kairo-trace"));

        Path old = traceDir.resolve("session-1000.jsonl");
        Files.writeString(old, "{\"phase\":\"POST_ACTING\",\"tool\":\"old-tool\",\"duration_ms\":1,\"status\":\"ok\",\"ts\":\"t\"}\n");

        // Ensure newer file has later lastModified
        Thread.sleep(10);
        Path newer = traceDir.resolve("session-2000.jsonl");
        Files.writeString(newer, "{\"phase\":\"POST_ACTING\",\"tool\":\"new-tool\",\"duration_ms\":2,\"status\":\"ok\",\"ts\":\"t\"}\n");

        ResponseEntity<List<TraceEntry>> response = controller.getTrace("any-session");
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).toolName()).isEqualTo("new-tool");
    }

    @Test
    void getTrace_blankLines_skipped() throws IOException {
        Path traceDir = Files.createDirectories(tempDir.resolve(".kairo-trace"));
        String jsonl = "\n\n{\"phase\":\"POST_ACTING\",\"tool\":\"t\",\"duration_ms\":1,\"status\":\"ok\",\"ts\":\"ts\"}\n\n";
        Files.writeString(traceDir.resolve("session-3000.jsonl"), jsonl);

        ResponseEntity<List<TraceEntry>> response = controller.getTrace("any-session");
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void getTrace_nonPostActingPhase_skipped() throws IOException {
        Path traceDir = Files.createDirectories(tempDir.resolve(".kairo-trace"));
        String jsonl =
                "{\"phase\":\"tool_start\",\"tool\":\"bash\",\"duration_ms\":50,\"status\":\"ok\",\"ts\":\"t1\"}\n" +
                "{\"phase\":\"POST_ACTING\",\"tool\":\"read\",\"duration_ms\":30,\"status\":\"ok\",\"ts\":\"t2\"}\n" +
                "{\"phase\":\"tool_end\",\"tool\":\"write\",\"duration_ms\":10,\"status\":\"ok\",\"ts\":\"t3\"}\n";
        Files.writeString(traceDir.resolve("session-4000.jsonl"), jsonl);

        ResponseEntity<List<TraceEntry>> response = controller.getTrace("any-session");
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).toolName()).isEqualTo("read");
    }
}
