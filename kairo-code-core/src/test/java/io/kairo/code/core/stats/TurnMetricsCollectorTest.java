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
package io.kairo.code.core.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.hook.ToolResultEvent;
import io.kairo.api.tool.ToolResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TurnMetricsCollectorTest {

    private static ToolResultEvent toolEvent(String tool, boolean success, long millis) {
        ToolResult result = new ToolResult("id1", "output", !success, Map.of());
        return new ToolResultEvent(tool, result, Duration.ofMillis(millis), success);
    }

    private static PostReasoningEvent postReasoningEvent() {
        return new PostReasoningEvent(null, false);
    }

    @Test
    void emptyState_returnsZeros() {
        TurnMetricsCollector collector = new TurnMetricsCollector();

        assertThat(collector.totalTurns()).isZero();
        assertThat(collector.totalToolCalls()).isZero();
        assertThat(collector.avgToolCallsPerTurn()).isZero();
        assertThat(collector.lastTurn()).isNull();
        assertThat(collector.turnSnapshots()).isEmpty();
    }

    @Test
    void oneTurn_withThreeToolCalls_recordedCorrectly() {
        TurnMetricsCollector collector = new TurnMetricsCollector();

        collector.onToolResult(toolEvent("bash", true, 100));
        collector.onToolResult(toolEvent("read", true, 50));
        collector.onToolResult(toolEvent("edit", false, 200));
        collector.onPostReasoning(postReasoningEvent());

        assertThat(collector.totalTurns()).isEqualTo(1);
        assertThat(collector.totalToolCalls()).isEqualTo(3);

        TurnMetricsCollector.TurnSnapshot snapshot = collector.lastTurn();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.turnNumber()).isEqualTo(1);
        assertThat(snapshot.toolCalls()).isEqualTo(3);
        assertThat(snapshot.successes()).isEqualTo(2);
    }

    @Test
    void avgToolCallsPerTurn_computedCorrectly() {
        TurnMetricsCollector collector = new TurnMetricsCollector();

        // Turn 1: 3 tool calls
        collector.onToolResult(toolEvent("bash", true, 100));
        collector.onToolResult(toolEvent("read", true, 50));
        collector.onToolResult(toolEvent("edit", true, 200));
        collector.onPostReasoning(postReasoningEvent());

        // Turn 2: 5 tool calls
        collector.onToolResult(toolEvent("bash", true, 100));
        collector.onToolResult(toolEvent("read", true, 50));
        collector.onToolResult(toolEvent("edit", true, 200));
        collector.onToolResult(toolEvent("bash", true, 100));
        collector.onToolResult(toolEvent("read", true, 50));
        collector.onPostReasoning(postReasoningEvent());

        // avg = (3 + 5) / 2 = 4.0
        assertThat(collector.avgToolCallsPerTurn()).isEqualTo(4.0);
    }

    @Test
    void turnSnapshots_returnsImmutableList() {
        TurnMetricsCollector collector = new TurnMetricsCollector();
        collector.onToolResult(toolEvent("bash", true, 100));
        collector.onPostReasoning(postReasoningEvent());

        List<TurnMetricsCollector.TurnSnapshot> snapshots = collector.turnSnapshots();
        assertThatThrownBy(() -> snapshots.add(
                new TurnMetricsCollector.TurnSnapshot(99, 1, 1, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void multipleTurns_snapshotsAreAccurate() {
        TurnMetricsCollector collector = new TurnMetricsCollector();

        // Turn 1: 2 calls
        collector.onToolResult(toolEvent("bash", true, 100));
        collector.onToolResult(toolEvent("read", true, 50));
        collector.onPostReasoning(postReasoningEvent());

        // Turn 2: 1 call
        collector.onToolResult(toolEvent("edit", false, 200));
        collector.onPostReasoning(postReasoningEvent());

        // Turn 3: 0 calls (post-reasoning only, no tools)
        collector.onPostReasoning(postReasoningEvent());

        List<TurnMetricsCollector.TurnSnapshot> snapshots = collector.turnSnapshots();
        // Only turns with tool calls are recorded
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).turnNumber()).isEqualTo(1);
        assertThat(snapshots.get(0).toolCalls()).isEqualTo(2);
        assertThat(snapshots.get(1).turnNumber()).isEqualTo(2);
        assertThat(snapshots.get(1).toolCalls()).isEqualTo(1);
    }

    @Test
    void totalDurationMillis_accumulates() {
        TurnMetricsCollector collector = new TurnMetricsCollector();

        collector.onToolResult(toolEvent("bash", true, 100));
        collector.onToolResult(toolEvent("read", true, 50));
        collector.onPostReasoning(postReasoningEvent());

        collector.onToolResult(toolEvent("edit", true, 200));
        collector.onPostReasoning(postReasoningEvent());

        // Total = (100 + 50) + 200 = 350
        assertThat(collector.totalDurationMillis()).isEqualTo(350);
    }

    @Test
    void turnSnapshot_successRate_computedCorrectly() {
        TurnMetricsCollector collector = new TurnMetricsCollector();

        collector.onToolResult(toolEvent("bash", true, 100));
        collector.onToolResult(toolEvent("bash", false, 100));
        collector.onToolResult(toolEvent("bash", true, 100));
        collector.onPostReasoning(postReasoningEvent());

        TurnMetricsCollector.TurnSnapshot snapshot = collector.lastTurn();
        assertThat(snapshot).isNotNull();
        // 2/3 = 66.666...%
        assertThat(snapshot.successRate()).isCloseTo(66.67,
                org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void turnSnapshot_successRate_zeroWhenNoCalls() {
        TurnMetricsCollector.TurnSnapshot snapshot =
                new TurnMetricsCollector.TurnSnapshot(1, 0, 0, 100);
        assertThat(snapshot.successRate()).isEqualTo(0.0);
    }
}
