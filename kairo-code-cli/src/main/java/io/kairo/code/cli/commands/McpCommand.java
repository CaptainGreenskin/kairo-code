package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.mcp.McpClientRegistry;
import io.kairo.mcp.McpToolGroup;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP server management command.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code :mcp list} — list connected MCP servers and their tools</li>
 * </ul>
 */
public class McpCommand implements SlashCommand {

    @Override
    public String name() {
        return "mcp";
    }

    @Override
    public String description() {
        return "Manage MCP servers";
    }

    @Override
    public void execute(String args, ReplContext context) {
        String sub = args == null || args.isBlank() ? "list" : args.trim().toLowerCase();

        if (!"list".equals(sub)) {
            context.writer().println("Unknown subcommand. Usage: :mcp list");
            context.writer().flush();
            return;
        }
        listServers(context);
    }

    private void listServers(ReplContext context) {
        CodeAgentSession session = context.session();
        if (session == null || session.mcpRegistry() == null) {
            context.writer().println("No MCP servers configured.");
            context.writer().println("Add servers to ~/.kairo-code/mcp.json and restart.");
            context.writer().flush();
            return;
        }

        McpClientRegistry registry = session.mcpRegistry();
        var serverNames = registry.getServerNames();
        if (serverNames.isEmpty()) {
            context.writer().println("No MCP servers connected.");
            context.writer().flush();
            return;
        }

        context.writer().println("MCP Servers:");
        // Sort for deterministic output
        List<String> sorted = new ArrayList<>(serverNames);
        sorted.sort(String::compareTo);

        for (String name : sorted) {
            McpToolGroup group = registry.getToolGroup(name);
            int toolCount = group != null ? group.size() : 0;
            String toolList = group != null ? String.join(", ", group.getRegisteredToolNames()) : "";
            context.writer().printf("  %-15s %d tool%s  (%s)%n",
                    name, toolCount, toolCount == 1 ? "" : "s", toolList);
        }
        context.writer().flush();
    }
}
