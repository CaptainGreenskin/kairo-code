package io.kairo.code.cli.commands;

import io.kairo.api.cost.UsageSummary;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.core.cost.CostEstimator;
import java.io.PrintWriter;

/**
 * Shows token usage and estimated USD cost for the current session.
 *
 * <p>Usage: {@code :cost}
 */
public class CostCommand implements SlashCommand {

    @Override
    public String name() {
        return "cost";
    }

    @Override
    public String description() {
        return "Show token usage and estimated cost for this session";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        String modelName = context.config().modelName();
        UsageSummary summary = context.session().costTracker().summary();

        writer.println();
        writer.println("Session Token Usage");
        writer.println("──────────────────────────────");
        writer.printf("Input tokens  : %,d%n", summary.inputTokens());
        writer.printf("Output tokens : %,d%n", summary.outputTokens());
        if (summary.cacheReadTokens() > 0 || summary.cacheCreationTokens() > 0) {
            writer.printf("Cache read    : %,d%n", summary.cacheReadTokens());
            writer.printf("Cache write   : %,d%n", summary.cacheCreationTokens());
        }
        writer.printf("Total tokens  : %,d%n", summary.totalTokens());
        writer.printf("Model calls   : %d%n", summary.callCount());
        writer.printf("Model         : %s%n", modelName != null ? modelName : "unknown");
        if (summary.estimatedCostUsd() > 0) {
            writer.printf(
                    "Est. cost     : ~%s USD%n",
                    CostEstimator.format(summary.estimatedCostUsd()));
            writer.println("                (public pricing, indicative only)");
        } else {
            writer.println("Est. cost     : unknown model pricing");
        }
        writer.println();
        writer.flush();
    }
}
