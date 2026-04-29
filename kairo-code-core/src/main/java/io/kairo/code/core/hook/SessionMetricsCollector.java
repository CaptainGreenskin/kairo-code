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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe session metrics collector for tool call distribution, redundant file reads,
 * idle iterations, and hook interventions.
 *
 * <p>Used by {@link SessionResultWriterHook} to enrich KAIRO_SESSION_RESULT.json with
 * efficiency metrics that agents can self-diagnose.
 */
public final class SessionMetricsCollector {

    // Tool call counts: {"bash_execute": 5, "read_file": 8}
    private final ConcurrentHashMap<String, AtomicInteger> toolCallCounts = new ConcurrentHashMap<>();

    // File read counts: {"src/Foo.java": 3} — for detecting redundant reads
    private final ConcurrentHashMap<String, AtomicInteger> fileReadCounts = new ConcurrentHashMap<>();

    // Iterations where the model replied with pure text (no tool calls)
    private final AtomicInteger iterationsWithoutTools = new AtomicInteger();

    // Hook intervention counts: {"CompileErrorFeedbackHook": 2}
    private final ConcurrentHashMap<String, AtomicInteger> hookInterventions = new ConcurrentHashMap<>();

    public void recordToolCall(String toolName) {
        if (toolName == null || toolName.isBlank()) return;
        toolCallCounts.computeIfAbsent(toolName, k -> new AtomicInteger(0)).incrementAndGet();
    }

    public void recordFileRead(String filePath) {
        if (filePath == null || filePath.isBlank()) return;
        fileReadCounts.computeIfAbsent(filePath, k -> new AtomicInteger(0)).incrementAndGet();
    }

    public void recordIterationWithoutTools() {
        iterationsWithoutTools.incrementAndGet();
    }

    public void recordHookIntervention(String hookName) {
        if (hookName == null || hookName.isBlank()) return;
        hookInterventions.computeIfAbsent(hookName, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /**
     * Returns file reads that occurred >= 2 times, sorted by count descending.
     */
    public List<Map.Entry<String, Integer>> redundantReads() {
        List<Map.Entry<String, Integer>> result = new ArrayList<>();
        for (Map.Entry<String, AtomicInteger> entry : fileReadCounts.entrySet()) {
            int count = entry.getValue().get();
            if (count >= 2) {
                result.add(Map.entry(entry.getKey(), count));
            }
        }
        result.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        return result;
    }

    /**
     * Produces a JSON fragment suitable for embedding into the session result.
     * Returns an empty string if no metrics have been recorded.
     */
    public String toJsonFragment() {
        boolean hasData = !toolCallCounts.isEmpty()
                || !fileReadCounts.isEmpty()
                || iterationsWithoutTools.get() > 0
                || !hookInterventions.isEmpty();
        if (!hasData) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // toolCallCounts
        sb.append(",\n  \"toolCallCounts\": {");
        appendMap(toolCallCounts, sb);
        sb.append("}");

        // redundantReads
        sb.append(",\n  \"redundantReads\": [");
        List<Map.Entry<String, Integer>> redundant = redundantReads();
        for (int i = 0; i < redundant.size(); i++) {
            if (i > 0) sb.append(", ");
            Map.Entry<String, Integer> e = redundant.get(i);
            sb.append("{\"file\": \"").append(escapeJson(e.getKey()))
              .append("\", \"count\": ").append(e.getValue()).append("}");
        }
        sb.append("]");

        // iterationsWithoutTools
        sb.append(",\n  \"iterationsWithoutTools\": ")
          .append(iterationsWithoutTools.get());

        // hookInterventions
        sb.append(",\n  \"hookInterventions\": {");
        appendMap(hookInterventions, sb);
        sb.append("}");

        return sb.toString();
    }

    private static void appendMap(ConcurrentHashMap<String, AtomicInteger> map, StringBuilder sb) {
        boolean first = true;
        for (Map.Entry<String, AtomicInteger> entry : map.entrySet()) {
            if (!first) sb.append(", ");
            sb.append("\"").append(escapeJson(entry.getKey())).append("\": ")
              .append(entry.getValue().get());
            first = false;
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }
}
