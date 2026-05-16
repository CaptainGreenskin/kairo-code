package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.mcp.KairoMcpServer;
import java.util.Set;

/**
 * MCP server mode command — exposes registered Kairo tools as an MCP server.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code :mcp-server start [tool1,tool2,...]} — start the server (optional tool whitelist)</li>
 *   <li>{@code :mcp-server stop} — stop the server</li>
 *   <li>{@code :mcp-server status} — show server status</li>
 * </ul>
 */
public class McpServerCommand implements SlashCommand {

    private volatile KairoMcpServer mcpServer;

    @Override
    public String name() {
        return "mcp-server";
    }

    @Override
    public String description() {
        return "Manage MCP server mode (start/stop/status)";
    }

    @Override
    public void execute(String args, ReplContext context) {
        String[] parts =
                (args == null || args.isBlank())
                        ? new String[] {"status"}
                        : args.trim().split("\\s+", 2);
        String sub = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (sub) {
            case "start" -> startServer(arg, context);
            case "stop" -> stopServer(context);
            case "status" -> showStatus(context);
            default -> {
                context.writer().println("Usage: :mcp-server start|stop|status");
                context.writer().flush();
            }
        }
    }

    private void startServer(String toolList, ReplContext context) {
        if (mcpServer != null && mcpServer.isRunning()) {
            context.writer().println("MCP server is already running.");
            context.writer().flush();
            return;
        }

        CodeAgentSession session = context.session();
        if (session == null) {
            context.writer().println("No active session. Cannot start MCP server.");
            context.writer().flush();
            return;
        }

        KairoMcpServer.Builder builder =
                KairoMcpServer.builder()
                        .serverName("kairo-code")
                        .toolExecutor(session.toolExecutor())
                        .tools(session.toolRegistry().getAll());

        if (!toolList.isBlank()) {
            Set<String> allowed = Set.of(toolList.split(","));
            builder.allowedTools(allowed);
        }

        mcpServer = builder.build();
        mcpServer.startStdio().subscribe();

        context.writer().println("MCP server started (stdio transport).");
        context.writer()
                .println("Exposing " + mcpServer.exposedTools().size() + " tools.");
        context.writer().flush();
    }

    private void stopServer(ReplContext context) {
        if (mcpServer == null || !mcpServer.isRunning()) {
            context.writer().println("MCP server is not running.");
            context.writer().flush();
            return;
        }
        mcpServer.stop().block();
        mcpServer = null;
        context.writer().println("MCP server stopped.");
        context.writer().flush();
    }

    private void showStatus(ReplContext context) {
        if (mcpServer == null || !mcpServer.isRunning()) {
            context.writer().println("MCP server: not running");
        } else {
            context.writer().println("MCP server: running");
            context.writer()
                    .println("Exposed tools: " + mcpServer.exposedTools().size());
            mcpServer.exposedTools().forEach(t ->
                    context.writer().println("  - " + t.name()));
        }
        context.writer().flush();
    }
}
