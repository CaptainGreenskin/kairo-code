package io.kairo.code.core.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.hook.ToolResultEvent;
import io.kairo.api.tool.ToolResult;
import io.kairo.code.core.evolution.LearnedLessonStore;
import io.kairo.code.core.stats.ToolUsageTracker;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionContextEnricherTest {

    private static final String BASE = "You are Kairo Code.";

    private static ToolResultEvent event(String tool, boolean success, long millis) {
        ToolResult result = new ToolResult("id", "output", !success, Map.of());
        return new ToolResultEvent(tool, result, Duration.ofMillis(millis), success);
    }

    private static void record(ToolUsageTracker tracker, String tool, int total, int successes, long perCallMs) {
        for (int i = 0; i < total; i++) {
            tracker.onToolResult(event(tool, i < successes, perCallMs));
        }
    }

    @Test
    void enrich_appendsToolInsightsWhenTrackerHasData() {
        ToolUsageTracker tracker = new ToolUsageTracker();
        record(tracker, "bash", 45, 41, 234);
        record(tracker, "edit", 20, 17, 45);

        String out = SessionContextEnricher.enrich(BASE, tracker, null);

        assertThat(out).startsWith(BASE);
        assertThat(out).contains("## Tool Insights (this session)");
        assertThat(out).contains("- bash: 45 calls, 91.1% success, avg 234ms");
        assertThat(out).contains("- edit: 20 calls, 85.0% success, avg 45ms");
    }

    @Test
    void enrich_appendsLearnedLessonsWhenStoreHasApproved(@TempDir Path tmp) {
        LearnedLessonStore store = LearnedLessonStore.fromKairoDir(tmp);
        store.save(LearnedLessonStore.Lesson.create(
                "bash", "Quote paths that contain spaces", LearnedLessonStore.Status.APPROVED));
        store.save(LearnedLessonStore.Lesson.create(
                "edit", "Pending lesson — should not appear", LearnedLessonStore.Status.PENDING));

        String out = SessionContextEnricher.enrich(BASE, null, store);

        assertThat(out).contains("## Learned Lessons");
        assertThat(out).contains("- [bash] Quote paths that contain spaces");
        assertThat(out).doesNotContain("Pending lesson");
    }

    @Test
    void enrich_returnsBaseWhenBothSourcesEmpty(@TempDir Path tmp) {
        ToolUsageTracker emptyTracker = new ToolUsageTracker();
        LearnedLessonStore emptyStore = LearnedLessonStore.fromKairoDir(tmp);

        assertThat(SessionContextEnricher.enrich(BASE, null, null)).isEqualTo(BASE);
        assertThat(SessionContextEnricher.enrich(BASE, emptyTracker, emptyStore)).isEqualTo(BASE);
    }

    @Test
    void enrich_sortsToolsByCallsDescending() {
        ToolUsageTracker tracker = new ToolUsageTracker();
        record(tracker, "edit", 5, 5, 10);
        record(tracker, "bash", 50, 50, 10);
        record(tracker, "grep", 20, 20, 10);

        String out = SessionContextEnricher.enrich(BASE, tracker, null);

        int bashIdx = out.indexOf("- bash:");
        int grepIdx = out.indexOf("- grep:");
        int editIdx = out.indexOf("- edit:");
        assertThat(bashIdx).isPositive();
        assertThat(grepIdx).isGreaterThan(bashIdx);
        assertThat(editIdx).isGreaterThan(grepIdx);
    }

    @Test
    void enrich_truncatesToTopTenTools() {
        ToolUsageTracker tracker = new ToolUsageTracker();
        // 12 tools, call counts 12,11,...,1 — only top 10 should render.
        for (int i = 1; i <= 12; i++) {
            record(tracker, "tool" + String.format("%02d", i), i, i, 10);
        }

        String out = SessionContextEnricher.enrich(BASE, tracker, null);

        // Top 10 by calls desc: tool12..tool03 → tool01 and tool02 must be excluded.
        assertThat(out).contains("- tool12:");
        assertThat(out).contains("- tool03:");
        assertThat(out).doesNotContain("- tool02:");
        assertThat(out).doesNotContain("- tool01:");

        long lineCount = out.lines()
                .filter(l -> l.startsWith("- tool"))
                .count();
        assertThat(lineCount).isEqualTo(10);
    }

    @Test
    void enrich_appendsBothSectionsWhenBothPresent(@TempDir Path tmp) {
        ToolUsageTracker tracker = new ToolUsageTracker();
        record(tracker, "bash", 3, 3, 100);
        LearnedLessonStore store = LearnedLessonStore.fromKairoDir(tmp);
        store.save(LearnedLessonStore.Lesson.create(
                "bash", "always quote paths", LearnedLessonStore.Status.APPROVED));

        String out = SessionContextEnricher.enrich(BASE, tracker, store);

        int insightsIdx = out.indexOf("## Tool Insights");
        int lessonsIdx = out.indexOf("## Learned Lessons");
        assertThat(insightsIdx).isPositive();
        assertThat(lessonsIdx).isGreaterThan(insightsIdx);
    }

    @Test
    void enrich_handlesNullBasePromptGracefully() {
        ToolUsageTracker tracker = new ToolUsageTracker();
        record(tracker, "bash", 1, 1, 5);

        String out = SessionContextEnricher.enrich(null, tracker, null);

        assertThat(out).contains("## Tool Insights (this session)");
        assertThat(out).contains("- bash: 1 calls, 100.0% success, avg 5ms");
    }
}
