package io.kairo.code.core.prompt;

import io.kairo.code.core.evolution.LearnedLessonStore;
import io.kairo.code.core.stats.ToolUsageTracker;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Appends session-scoped runtime context to the base system prompt: per-tool usage statistics
 * collected by {@link ToolUsageTracker} during the current session, and approved entries from
 * {@link LearnedLessonStore}.
 *
 * <p>Both sources are optional; sections are only emitted when the corresponding source has data.
 */
public final class SessionContextEnricher {

    /** Maximum number of tools rendered in the Tool Insights section, sorted by call count desc. */
    static final int MAX_TOOL_INSIGHTS = 10;

    private SessionContextEnricher() {}

    /**
     * Append Tool Insights and Learned Lessons sections to {@code baseSystemPrompt}.
     *
     * <p>Returns the original prompt unchanged when neither source contributes data.
     */
    public static String enrich(
            String baseSystemPrompt,
            ToolUsageTracker tracker,
            LearnedLessonStore lessonStore) {
        StringBuilder sb = new StringBuilder(baseSystemPrompt == null ? "" : baseSystemPrompt);
        appendToolInsights(sb, tracker);
        appendLearnedLessons(sb, lessonStore);
        return sb.toString();
    }

    private static void appendToolInsights(StringBuilder sb, ToolUsageTracker tracker) {
        if (tracker == null) return;
        Map<String, ToolUsageTracker.ToolStat> snapshot = tracker.snapshot();
        if (snapshot == null || snapshot.isEmpty()) return;

        List<Map.Entry<String, ToolUsageTracker.ToolStat>> top = snapshot.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().calls() > 0)
                .sorted(Comparator
                        .comparingLong((Map.Entry<String, ToolUsageTracker.ToolStat> e) ->
                                e.getValue().calls())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(MAX_TOOL_INSIGHTS)
                .toList();
        if (top.isEmpty()) return;

        sb.append("\n\n## Tool Insights (this session)\n");
        for (var e : top) {
            ToolUsageTracker.ToolStat stat = e.getValue();
            sb.append("- ")
                    .append(e.getKey())
                    .append(": ")
                    .append(stat.calls())
                    .append(" calls, ")
                    .append(formatPercent(stat.successRate()))
                    .append("% success, avg ")
                    .append(stat.avgMillis())
                    .append("ms\n");
        }
    }

    private static void appendLearnedLessons(StringBuilder sb, LearnedLessonStore lessonStore) {
        if (lessonStore == null) return;
        var approved = lessonStore.listApproved();
        if (approved == null || approved.isEmpty()) return;

        sb.append("\n\n## Learned Lessons\n");
        for (var l : approved) {
            sb.append("- [").append(l.toolName()).append("] ").append(l.lessonText()).append("\n");
        }
    }

    private static String formatPercent(double pct) {
        // 1 decimal place, no trailing zero stripping (matches "91.1" / "85.0" style in spec).
        return String.format(java.util.Locale.ROOT, "%.1f", pct);
    }
}
