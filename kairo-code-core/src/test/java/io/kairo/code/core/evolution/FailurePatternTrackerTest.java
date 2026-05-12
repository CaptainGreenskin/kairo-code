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
        ToolResult result = success ? ToolResult.success("id1", "output") : ToolResult.error("id1", "output");
        return new ToolResultEvent(tool, result, Duration.ofMillis(100), success);
    }

    private static ToolResultEvent event(String tool, boolean success, String content) {
        ToolResult result = success ? ToolResult.success("id1", content) : ToolResult.error("id1", content);
        return new ToolResultEvent(tool, result, Duration.ofMillis(100), success);
    }

    @Test
    void noStrike_beforeThreshold() {
        List<ToolStrikeEvent> strikes = new ArrayList<>();
        FailurePatternTracker tracker = new FailurePatternTracker(strikes::add);

        tracker.onToolResult(event("bash", false));
        tracker.onToolResult(event("bash", false));

        assertThat(strikes).isEmpty();
        assertThat(tracker.consecutiveFailureCount("bash")).isEqualTo(2);
    }

    @Test
    void strike3_firesCallback() {
        List<ToolStrikeEvent> strikes = new ArrayList<>();
        FailurePatternTracker tracker = new FailurePatternTracker(strikes::add);

        tracker.onToolResult(event("bash", false, "error 1"));
        tracker.onToolResult(event("bash", false, "error 2"));
        tracker.onToolResult(event("bash", false, "error 3"));

        assertThat(strikes).hasSize(1);
        ToolStrikeEvent event = strikes.get(0);
        assertThat(event.toolName()).isEqualTo("bash");
        assertThat(event.recentErrors()).containsExactly("error 1", "error 2", "error 3");
        assertThat(tracker.consecutiveFailureCount("bash")).isEqualTo(0);
    }

    @Test
    void successResetsCounter() {
        List<ToolStrikeEvent> strikes = new ArrayList<>();
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
        List<ToolStrikeEvent> strikes = new ArrayList<>();
        FailurePatternTracker tracker = new FailurePatternTracker(strikes::add);

        tracker.onToolResult(event("bash", false, "bash err 1"));
        tracker.onToolResult(event("grep", false, "grep err 1"));
        tracker.onToolResult(event("bash", false, "bash err 2"));
        tracker.onToolResult(event("grep", false, "grep err 2"));
        tracker.onToolResult(event("bash", false, "bash err 3"));
        tracker.onToolResult(event("grep", false, "grep err 3"));

        assertThat(strikes).hasSize(2);
        assertThat(strikes)
                .extracting(ToolStrikeEvent::toolName)
                .containsExactlyInAnyOrder("bash", "grep");

        // Verify each event has correct errors
        ToolStrikeEvent bashEvent = strikes.stream()
                .filter(e -> e.toolName().equals("bash")).findFirst().orElseThrow();
        assertThat(bashEvent.recentErrors())
                .containsExactly("bash err 1", "bash err 2", "bash err 3");

        ToolStrikeEvent grepEvent = strikes.stream()
                .filter(e -> e.toolName().equals("grep")).findFirst().orElseThrow();
        assertThat(grepEvent.recentErrors())
                .containsExactly("grep err 1", "grep err 2", "grep err 3");
    }

    @Test
    void counterResets_afterStrike_allowsNextStrike() {
        List<ToolStrikeEvent> strikes = new ArrayList<>();
        FailurePatternTracker tracker = new FailurePatternTracker(strikes::add);

        for (int i = 0; i < 6; i++) {
            tracker.onToolResult(event("bash", false, "error " + (i + 1)));
        }

        assertThat(strikes).hasSize(2);

        // Second strike should have errors 4, 5, 6
        ToolStrikeEvent second = strikes.get(1);
        assertThat(second.recentErrors()).containsExactly("error 4", "error 5", "error 6");
    }

    @Test
    void recentErrorsCappedAtThree() {
        List<ToolStrikeEvent> strikes = new ArrayList<>();
        FailurePatternTracker tracker = new FailurePatternTracker(strikes::add);

        // 5 failures — only last 3 should be in the event
        for (int i = 0; i < 5; i++) {
            tracker.onToolResult(event("bash", false, "error " + (i + 1)));
        }

        // Strike fires at 3, then counter resets and fires again at 6
        // But we only have 5, so only 1 strike
        assertThat(strikes).hasSize(1);
        assertThat(strikes.get(0).recentErrors()).containsExactly("error 1", "error 2", "error 3");
    }

    @Test
    void recentErrorsFor_exposesErrors() {
        List<ToolStrikeEvent> strikes = new ArrayList<>();
        FailurePatternTracker tracker = new FailurePatternTracker(strikes::add);

        tracker.onToolResult(event("bash", false, "err A"));
        tracker.onToolResult(event("bash", false, "err B"));

        assertThat(tracker.recentErrorsFor("bash")).containsExactly("err A", "err B");
        assertThat(tracker.recentErrorsFor("unknown")).isEmpty();
    }

    @Test
    void successClearsRecentErrors() {
        List<ToolStrikeEvent> strikes = new ArrayList<>();
        FailurePatternTracker tracker = new FailurePatternTracker(strikes::add);

        tracker.onToolResult(event("bash", false, "err A"));
        tracker.onToolResult(event("bash", true));
        tracker.onToolResult(event("bash", false, "err B"));

        assertThat(tracker.recentErrorsFor("bash")).containsExactly("err B");
    }
}
