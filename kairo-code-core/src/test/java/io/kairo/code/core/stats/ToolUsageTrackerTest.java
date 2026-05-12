package io.kairo.code.core.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kairo.api.hook.ToolResultEvent;
import io.kairo.api.tool.ToolResult;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolUsageTrackerTest {

    private static ToolResultEvent event(String tool, boolean success, long millis) {
        ToolResult result = success ? ToolResult.success("id1", "output") : ToolResult.error("id1", "output");
        return new ToolResultEvent(tool, result, Duration.ofMillis(millis), success);
    }

    @Test
    void recordsSuccess_callsAndSuccessesIncremented() {
        ToolUsageTracker tracker = new ToolUsageTracker();

        tracker.onToolResult(event("bash", true, 100));
        tracker.onToolResult(event("bash", true, 200));

        var stats = tracker.snapshot();
        assertThat(stats).containsKey("bash");

        ToolUsageTracker.ToolStat stat = stats.get("bash");
        assertThat(stat.calls()).isEqualTo(2);
        assertThat(stat.successes()).isEqualTo(2);
        assertThat(stat.totalMillis()).isEqualTo(300);
    }

    @Test
    void recordsFailure_callsIncrementedSuccessesUnchanged() {
        ToolUsageTracker tracker = new ToolUsageTracker();

        tracker.onToolResult(event("bash", true, 100));
        tracker.onToolResult(event("bash", false, 50));

        var stats = tracker.snapshot();
        ToolUsageTracker.ToolStat stat = stats.get("bash");
        assertThat(stat.calls()).isEqualTo(2);
        assertThat(stat.successes()).isEqualTo(1);
        assertThat(stat.totalMillis()).isEqualTo(150);
    }

    @Test
    void snapshotReturnsImmutableMap() {
        ToolUsageTracker tracker = new ToolUsageTracker();
        tracker.onToolResult(event("bash", true, 100));

        var snapshot = tracker.snapshot();
        assertThatThrownBy(() -> snapshot.put("new", new ToolUsageTracker.ToolStat()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void differentToolsTrackedIndependently() {
        ToolUsageTracker tracker = new ToolUsageTracker();

        tracker.onToolResult(event("bash", true, 100));
        tracker.onToolResult(event("read", true, 10));
        tracker.onToolResult(event("edit", false, 50));

        var stats = tracker.snapshot();
        assertThat(stats).hasSize(3);

        assertThat(stats.get("bash").calls()).isEqualTo(1);
        assertThat(stats.get("read").calls()).isEqualTo(1);
        assertThat(stats.get("edit").calls()).isEqualTo(1);
        assertThat(stats.get("edit").successes()).isEqualTo(0);
    }

    @Test
    void successRate_calculation() {
        ToolUsageTracker tracker = new ToolUsageTracker();

        tracker.onToolResult(event("bash", true, 100));
        tracker.onToolResult(event("bash", true, 100));
        tracker.onToolResult(event("bash", false, 100));

        ToolUsageTracker.ToolStat stat = tracker.snapshot().get("bash");
        // 2/3 = 66.666...%
        assertThat(stat.successRate()).isCloseTo(66.67, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void successRate_zeroWhenNoCalls() {
        ToolUsageTracker.ToolStat stat = new ToolUsageTracker.ToolStat();
        assertThat(stat.successRate()).isEqualTo(0.0);
    }

    @Test
    void avgMillis_calculation() {
        ToolUsageTracker tracker = new ToolUsageTracker();

        tracker.onToolResult(event("bash", true, 100));
        tracker.onToolResult(event("bash", true, 200));
        tracker.onToolResult(event("bash", true, 300));

        ToolUsageTracker.ToolStat stat = tracker.snapshot().get("bash");
        assertThat(stat.avgMillis()).isEqualTo(200);
    }

    @Test
    void avgMillis_zeroWhenNoCalls() {
        ToolUsageTracker.ToolStat stat = new ToolUsageTracker.ToolStat();
        assertThat(stat.avgMillis()).isEqualTo(0);
    }

    @Test
    void snapshotIsEmptyWhenNoEvents() {
        ToolUsageTracker tracker = new ToolUsageTracker();
        assertThat(tracker.snapshot()).isEmpty();
    }
}
