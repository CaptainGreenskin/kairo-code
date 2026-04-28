package io.kairo.code.core.evolution;

import static org.assertj.core.api.Assertions.assertThat;
import io.kairo.api.hook.ToolResultEvent;
import io.kairo.api.tool.ToolResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FailurePatternTrackerTest {

    private static ToolResultEvent event(String tool, boolean success) {
        ToolResult result = new ToolResult("id1", "output", !success, Map.of());
        return new ToolResultEvent(tool, result, Duration.ofMillis(100), success);
    }

    @Test
    void noStrike_beforeThreshold() {
        List<String> strikes = new ArrayList<>();
        FailurePatternTracker tracker = new FailurePatternTracker(strikes::add);

        tracker.onToolResult(event("bash", false));
        tracker.onToolResult(event("bash", false));

        assertThat(strikes).isEmpty();
        assertThat(tracker.consecutiveFailureCount("bash")).isEqualTo(2);
    }

    @Test
    void strike3_firesCallback() {
        List<String> strikes = new ArrayList<>();
        FailurePatternTracker tracker = new FailurePatternTracker(strikes::add);

        tracker.onToolResult(event("bash", false));
        tracker.onToolResult(event("bash", false));
        tracker.onToolResult(event("bash", false));

        assertThat(strikes).containsExactly("bash");
        assertThat(tracker.consecutiveFailureCount("bash")).isEqualTo(0);
    }

    @Test
    void successResetsCounter() {
        List<String> strikes = new ArrayList<>();
        FailurePatternTracker tracker = new FailurePatternTracker(strikes::add);

        tracker.onToolResult(event("bash", false));
        tracker.onToolResult(event("bash", false));
        tracker.onToolResult(event("bash", true));
        tracker.onToolResult(event("bash", false));

        assertThat(strikes).isEmpty();
        assertThat(tracker.consecutiveFailureCount("bash")).isEqualTo(1);
    }

    @Test
    void differentTools_trackedIndependently() {
        List<String> strikes = new ArrayList<>();
        FailurePatternTracker tracker = new FailurePatternTracker(strikes::add);

        tracker.onToolResult(event("bash", false));
        tracker.onToolResult(event("grep", false));
        tracker.onToolResult(event("bash", false));
        tracker.onToolResult(event("grep", false));
        tracker.onToolResult(event("bash", false));
        tracker.onToolResult(event("grep", false));

        assertThat(strikes).containsExactlyInAnyOrder("bash", "grep");
    }

    @Test
    void counterResets_afterStrike_allowsNextStrike() {
        List<String> strikes = new ArrayList<>();
        FailurePatternTracker tracker = new FailurePatternTracker(strikes::add);

        for (int i = 0; i < 6; i++) {
            tracker.onToolResult(event("bash", false));
        }

        assertThat(strikes).hasSize(2);
    }
}
