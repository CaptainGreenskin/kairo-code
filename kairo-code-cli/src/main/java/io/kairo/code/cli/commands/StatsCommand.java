package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.stats.ToolUsageTracker;
import io.kairo.code.core.stats.ToolUsageTracker.ToolStat;
import io.kairo.core.guardrail.policy.BashCommandClassifier.Category;
import io.kairo.core.guardrail.policy.LlmBashClassifier;
import java.io.PrintWriter;
import java.util.Map;
import java.util.TreeMap;

/**
 * Displays per-tool usage statistics: call count, success rate, and average duration.
 *
 * <p>When the LLM bash-classifier fallback is wired (via {@code llmClassifier.enabled=true} in the
 * agent config), this command also prints a classifier section \u2014 verdict counts, cache hit-rate,
 * LLM call/failure counts, latency, and token usage. Without this surface there's no way for the
 * REPL user to confirm the fallback is actually firing (the only other signal is the SLF4J WARN
 * channel, which most operators don't tail).
 *
 * <p>Usage: {@code :stats}
 */
public class StatsCommand implements SlashCommand {

    private static final String SEPARATOR = "\u2500".repeat(42);

    @Override
    public String name() {
        return "stats";
    }

    @Override
    public String description() {
        return "Show per-tool call count, success rate, and avg duration";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        CodeAgentSession session = context.session();
        ToolUsageTracker tracker = session.toolUsageTracker();

        Map<String, ToolStat> stats = tracker.snapshot();
        boolean printedAnything = false;

        if (!stats.isEmpty()) {
            renderToolTable(writer, new TreeMap<>(stats));
            printedAnything = true;
        }

        LlmBashClassifier classifier = session.llmBashClassifier();
        if (classifier != null) {
            renderClassifierSection(writer, classifier);
            printedAnything = true;
        }

        if (!printedAnything) {
            writer.println("No tool usage recorded yet.");
        }
        writer.flush();
    }

    private static void renderToolTable(PrintWriter writer, Map<String, ToolStat> sorted) {
        writer.println();
        writer.println("Tool Usage Statistics");
        writer.println(SEPARATOR);
        writer.printf("%-16s %5s  %8s  %7s%n", "Tool", "Calls", "Success%", "Avg(ms)");
        writer.println(SEPARATOR);

        for (Map.Entry<String, ToolStat> entry : sorted.entrySet()) {
            String toolName = entry.getKey();
            ToolStat stat = entry.getValue();
            writer.printf("%-16s %5d  %7.1f%%  %7d%n",
                    toolName,
                    stat.calls(),
                    stat.successRate(),
                    stat.avgMillis());
        }

        writer.println(SEPARATOR);
    }

    private static void renderClassifierSection(PrintWriter writer, LlmBashClassifier classifier) {
        LlmBashClassifier.Stats s = classifier.snapshot();
        long totalLookups = s.cacheHits() + s.cacheMisses();
        double hitRate = totalLookups == 0 ? 0.0 : (s.cacheHits() * 100.0) / totalLookups;
        long avgLatencyMs = s.llmCalls() == 0 ? 0 : s.totalLatencyMillis() / s.llmCalls();

        writer.println();
        writer.println("LLM Bash Classifier");
        writer.println(SEPARATOR);
        writer.printf("LLM calls         : %d%n", s.llmCalls());
        writer.printf("LLM failures      : %d%n", s.llmFailures());
        writer.printf("Cache hits/misses : %d / %d  (%.1f%% hit-rate)%n",
                s.cacheHits(), s.cacheMisses(), hitRate);
        writer.printf("Avg LLM latency   : %d ms%n", avgLatencyMs);
        writer.printf("Tokens (in/out)   : %d / %d%n",
                s.totalInputTokens(), s.totalOutputTokens());

        // Render verdict breakdown only when at least one verdict was recorded \u2014 keeps the
        // section compact for sessions that never had an UNKNOWN command worth classifying.
        Map<Category, Long> verdicts = s.verdictCounts();
        long verdictTotal = 0L;
        for (long v : verdicts.values()) verdictTotal += v;
        if (verdictTotal > 0) {
            writer.println("Verdict breakdown :");
            for (Category c : Category.values()) {
                long count = verdicts.getOrDefault(c, 0L);
                if (count > 0) {
                    writer.printf("  %-12s %d%n", c.name(), count);
                }
            }
        }

        writer.println(SEPARATOR);
    }
}
