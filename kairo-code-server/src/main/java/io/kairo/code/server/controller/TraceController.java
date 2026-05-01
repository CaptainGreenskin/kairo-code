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
package io.kairo.code.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads ExecutionTraceHook JSONL files and returns structured tool-call timeline entries.
 *
 * <p>Trace files live in {@code {workingDir}/.kairo-trace/session-{timestamp}.jsonl}.
 * The sessionId path variable is ignored for file lookup (files are named by timestamp),
 * so we always return the latest trace file.
 */
@RestController
@RequestMapping("/api/trace")
public class TraceController {

    private final Path workingDir;
    private final ObjectMapper objectMapper;

    public TraceController(ServerProperties props, ObjectMapper objectMapper) {
        this.workingDir = Path.of(props.workingDir());
        this.objectMapper = objectMapper;
    }

    public record TraceEntry(
            String phase,
            String toolName,
            long durationMs,
            String status,
            String ts
    ) {}

    @GetMapping("/{sessionId}")
    public ResponseEntity<List<TraceEntry>> getTrace(@PathVariable String sessionId) throws IOException {
        Path traceDir = workingDir.resolve(".kairo-trace");
        if (!Files.exists(traceDir)) {
            return ResponseEntity.ok(List.of());
        }

        Path traceFile = findLatestTraceFile(traceDir);
        if (traceFile == null) {
            return ResponseEntity.ok(List.of());
        }

        List<TraceEntry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(traceFile)) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = objectMapper.readTree(line);
                String phase = node.path("phase").asText("");
                if (!"POST_ACTING".equals(phase)) continue;
                entries.add(new TraceEntry(
                        phase,
                        node.path("tool").asText(""),
                        node.path("duration_ms").asLong(0),
                        node.path("status").asText(""),
                        node.path("ts").asText("")
                ));
            } catch (Exception e) {
                // skip malformed lines
            }
        }
        return ResponseEntity.ok(entries);
    }

    /** Returns the most recently modified .jsonl file in the trace directory. */
    private static Path findLatestTraceFile(Path traceDir) throws IOException {
        try (Stream<Path> files = Files.list(traceDir)) {
            return files
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        }
    }
}
