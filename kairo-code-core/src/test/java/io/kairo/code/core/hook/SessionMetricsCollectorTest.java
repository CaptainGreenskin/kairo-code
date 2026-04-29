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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionMetricsCollectorTest {

    @Test
    void recordToolCall_accumulatesCounts() {
        SessionMetricsCollector m = new SessionMetricsCollector();

        m.recordToolCall("bash_execute");
        m.recordToolCall("bash_execute");
        m.recordToolCall("read_file");
        m.recordToolCall("write_file");

        String json = m.toJsonFragment();
        assertThat(json).contains("\"bash_execute\": 2");
        assertThat(json).contains("\"read_file\": 1");
        assertThat(json).contains("\"write_file\": 1");
    }

    @Test
    void redundantReads_onlyReturnsCountGte2() {
        SessionMetricsCollector m = new SessionMetricsCollector();

        m.recordFileRead("src/A.java"); // 1
        m.recordFileRead("src/B.java"); // 1
        m.recordFileRead("src/B.java"); // 2
        m.recordFileRead("src/B.java"); // 3
        m.recordFileRead("src/C.java"); // 1
        m.recordFileRead("src/C.java"); // 2

        List<Map.Entry<String, Integer>> redundant = m.redundantReads();
        assertThat(redundant).hasSize(2);
        // sorted by count descending
        assertThat(redundant.get(0).getKey()).isEqualTo("src/B.java");
        assertThat(redundant.get(0).getValue()).isEqualTo(3);
        assertThat(redundant.get(1).getKey()).isEqualTo("src/C.java");
        assertThat(redundant.get(1).getValue()).isEqualTo(2);
    }

    @Test
    void toJsonFragment_formatIsCorrect() {
        SessionMetricsCollector m = new SessionMetricsCollector();

        m.recordToolCall("bash_execute");
        m.recordToolCall("read_file");
        m.recordFileRead("src/Foo.java");
        m.recordFileRead("src/Foo.java");
        m.recordIterationWithoutTools();
        m.recordHookIntervention("CompileErrorFeedbackHook");

        String json = m.toJsonFragment();
        assertThat(json).startsWith(",\n");
        assertThat(json).contains("\"toolCallCounts\": {");
        assertThat(json).contains("\"bash_execute\": 1");
        assertThat(json).contains("\"read_file\": 1");
        assertThat(json).contains("\"redundantReads\": [");
        assertThat(json).contains("\"file\": \"src/Foo.java\"");
        assertThat(json).contains("\"count\": 2");
        assertThat(json).contains("\"iterationsWithoutTools\": 1");
        assertThat(json).contains("\"hookInterventions\": {");
        assertThat(json).contains("\"CompileErrorFeedbackHook\": 1");
    }

    @Test
    void toJsonFragment_emptyWhenNoData() {
        SessionMetricsCollector m = new SessionMetricsCollector();
        assertThat(m.toJsonFragment()).isEmpty();
    }

    @Test
    void recordHookIntervention_accumulates() {
        SessionMetricsCollector m = new SessionMetricsCollector();

        m.recordHookIntervention("HookA");
        m.recordHookIntervention("HookA");
        m.recordHookIntervention("HookB");

        String json = m.toJsonFragment();
        assertThat(json).contains("\"HookA\": 2");
        assertThat(json).contains("\"HookB\": 1");
    }

    @Test
    void recordIterationWithoutTools_accumulates() {
        SessionMetricsCollector m = new SessionMetricsCollector();

        m.recordIterationWithoutTools();
        m.recordIterationWithoutTools();
        m.recordIterationWithoutTools();

        String json = m.toJsonFragment();
        assertThat(json).contains("\"iterationsWithoutTools\": 3");
    }

    @Test
    void recordToolCall_ignoresNullAndBlank() {
        SessionMetricsCollector m = new SessionMetricsCollector();

        m.recordToolCall(null);
        m.recordToolCall("");
        m.recordToolCall("  ");
        m.recordToolCall("valid_tool");

        String json = m.toJsonFragment();
        assertThat(json).contains("\"valid_tool\": 1");
        assertThat(json).doesNotContain("null");
    }
}
