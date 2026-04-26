package io.kairo.code.cli.commands;

import io.kairo.api.agent.AgentSnapshot;
import io.kairo.code.cli.AgentEventPrinter;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import java.io.PrintWriter;
import java.util.Map;

public class UsageCommand implements SlashCommand {

    @Override
    public String name() {
        return "usage";
    }

    @Override
    public String description() {
        return "Show token usage, iteration count, and top tools for this session";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        try {
            AgentSnapshot snapshot = context.agent().snapshot();
            writer.printf(
                    "total_tokens=%d  iterations=%d%n",
                    snapshot.totalTokensUsed(), snapshot.iteration());
        } catch (UnsupportedOperationException e) {
            writer.println("Token stats not available for this agent.");
        }

        AgentEventPrinter printer = context.eventPrinter();
        if (printer != null) {
            Map<String, Integer> counts = printer.getToolCallCounts();
            if (!counts.isEmpty()) {
                writer.println("Top tools:");
                counts.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .limit(3)
                        .forEach(e -> writer.printf("  %-20s %d calls%n", e.getKey(), e.getValue()));
            }
        }

        writer.flush();
    }
}
