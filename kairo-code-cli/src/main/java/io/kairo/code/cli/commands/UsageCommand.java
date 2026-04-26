package io.kairo.code.cli.commands;

import io.kairo.api.agent.AgentSnapshot;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import java.io.PrintWriter;

public class UsageCommand implements SlashCommand {

    @Override
    public String name() {
        return "usage";
    }

    @Override
    public String description() {
        return "Show token usage and iteration count for this session";
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
        writer.flush();
    }
}
