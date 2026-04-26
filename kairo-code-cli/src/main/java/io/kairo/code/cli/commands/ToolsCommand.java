package io.kairo.code.cli.commands;

import io.kairo.api.tool.ToolDefinition;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import java.io.PrintWriter;
import java.util.Comparator;
import java.util.List;

/** Lists all tools registered in the current agent session. */
public class ToolsCommand implements SlashCommand {

    @Override
    public String name() {
        return "tools";
    }

    @Override
    public String description() {
        return "List all registered tools and their descriptions";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        if (context.session() == null) {
            writer.println("No active session.");
            writer.flush();
            return;
        }
        List<ToolDefinition> tools = context.session().toolRegistry().getAll().stream()
                .sorted(Comparator.comparing(ToolDefinition::name))
                .toList();
        writer.printf("Registered tools (%d):%n", tools.size());
        for (ToolDefinition tool : tools) {
            writer.printf("  %-20s %s%n", tool.name(), tool.description());
        }
        writer.flush();
    }
}
