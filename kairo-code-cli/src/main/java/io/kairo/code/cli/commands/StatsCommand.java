package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.stats.ToolUsageTracker;
import io.kairo.code.core.stats.ToolUsageTracker.ToolStat;
import java.io.PrintWriter;
import java.util.Map;
import java.util.TreeMap;

/**
 * Displays per-tool usage statistics: call count, success rate, and average duration.
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
        if (stats.isEmpty()) {
            writer.println("No tool usage recorded yet.");
            writer.flush();
            return;
        }

        // Sort by tool name for consistent output
        Map<String, ToolStat> sorted = new TreeMap<>(stats);

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
        writer.flush();
    }
}
