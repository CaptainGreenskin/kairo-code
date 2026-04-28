package io.kairo.code.cli.commands;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentSnapshot;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.core.cost.CostEstimator;
import java.io.PrintWriter;
import java.util.OptionalDouble;

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
        Agent agent = context.agent();
        String modelName = context.config().modelName();

        long totalTokens = 0;
        boolean available = false;

        try {
            AgentSnapshot snapshot = agent.snapshot();
            totalTokens = snapshot.totalTokensUsed();
            available = true;
        } catch (UnsupportedOperationException e) {
            try {
                var method = agent.getClass().getMethod("totalTokensUsed");
                totalTokens = (long) method.invoke(agent);
                available = true;
            } catch (Exception ignored) {
                // Not available
            }
        }

        writer.println();
        writer.println("Session Token Usage");
        writer.println("──────────────────────────────");
        if (available) {
            writer.printf("Total tokens : %,d%n", totalTokens);
            writer.printf("Model        : %s%n", modelName != null ? modelName : "unknown");
            OptionalDouble cost = CostEstimator.estimate(modelName, totalTokens);
            if (cost.isPresent()) {
                writer.printf("Est. cost    : ~%s USD%n", CostEstimator.format(cost.getAsDouble()));
                writer.println("               (public pricing, indicative only)");
            } else {
                writer.println("Est. cost    : unknown model");
            }
        } else {
            writer.println("Token tracking not available for this agent.");
        }
        writer.println();
        writer.flush();
    }
}
