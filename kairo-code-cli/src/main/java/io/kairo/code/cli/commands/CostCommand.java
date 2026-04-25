package io.kairo.code.cli.commands;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentSnapshot;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import java.io.PrintWriter;

/**
 * Shows token usage for the current session.
 *
 * <p>Usage: {@code :cost}
 *
 * <p>Retrieves token usage from the agent's snapshot. If snapshot is not supported,
 * a helpful message is displayed.
 */
public class CostCommand implements SlashCommand {

    @Override
    public String name() {
        return "cost";
    }

    @Override
    public String description() {
        return "Show token usage for this session";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        Agent agent = context.agent();

        long totalTokens = 0;
        boolean available = false;

        try {
            AgentSnapshot snapshot = agent.snapshot();
            totalTokens = snapshot.totalTokensUsed();
            available = true;
        } catch (UnsupportedOperationException e) {
            // Snapshot not supported — try reflection for DefaultReActAgent.totalTokensUsed()
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
        writer.println("─────────────────────");
        if (available) {
            writer.printf("Total tokens: %,d%n", totalTokens);
        } else {
            writer.println("Token tracking not available for this agent.");
        }
        writer.println();
        writer.flush();
    }
}
