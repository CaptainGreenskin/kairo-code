package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.core.plugin.PluginRegistry;
import io.kairo.code.core.plugin.PluginRegistry.LoadedPlugin;
import java.util.List;

/**
 * Plugin management command.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code :plugin list} — list all discovered plugins</li>
 *   <li>{@code :plugin enable <name>} — enable a plugin</li>
 *   <li>{@code :plugin disable <name>} — disable a plugin</li>
 *   <li>{@code :plugin info <name>} — show plugin details</li>
 * </ul>
 */
public class PluginCommand implements SlashCommand {

    private final PluginRegistry registry;

    public PluginCommand(PluginRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "plugin";
    }

    @Override
    public String description() {
        return "Manage plugins (list/enable/disable/info)";
    }

    @Override
    public void execute(String args, ReplContext context) {
        if (registry == null) {
            context.writer().println("Plugin system not initialized.");
            context.writer().flush();
            return;
        }

        String[] parts = (args == null || args.isBlank()) ? new String[]{"list"} : args.trim().split("\\s+", 2);
        String sub = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (sub) {
            case "list" -> listPlugins(context);
            case "enable" -> togglePlugin(arg, true, context);
            case "disable" -> togglePlugin(arg, false, context);
            case "info" -> showInfo(arg, context);
            default -> {
                context.writer().println("Usage: :plugin list|enable|disable|info [name]");
                context.writer().flush();
            }
        }
    }

    private void listPlugins(ReplContext context) {
        List<LoadedPlugin> plugins = registry.list();
        if (plugins.isEmpty()) {
            context.writer().println("No plugins discovered.");
            context.writer().println("Add plugins to .kairo-code/plugins/<name>/plugin.yaml");
            context.writer().flush();
            return;
        }
        context.writer().println("Plugins:");
        for (LoadedPlugin p : plugins) {
            String status = p.enabled() ? "enabled" : "disabled";
            context.writer().printf("  %-20s %-8s  %s  v%s%n",
                    p.name(), "[" + status + "]",
                    p.manifest().description(),
                    p.manifest().version());
        }
        context.writer().flush();
    }

    private void togglePlugin(String name, boolean enable, ReplContext context) {
        if (name.isEmpty()) {
            context.writer().println("Usage: :plugin " + (enable ? "enable" : "disable") + " <name>");
            context.writer().flush();
            return;
        }
        boolean found = enable ? registry.enable(name) : registry.disable(name);
        if (found) {
            context.writer().println("Plugin '" + name + "' " + (enable ? "enabled" : "disabled") + ".");
            context.writer().println("Run :skill reload to pick up changes.");
        } else {
            context.writer().println("Plugin '" + name + "' not found.");
        }
        context.writer().flush();
    }

    private void showInfo(String name, ReplContext context) {
        if (name.isEmpty()) {
            context.writer().println("Usage: :plugin info <name>");
            context.writer().flush();
            return;
        }
        var opt = registry.get(name);
        if (opt.isEmpty()) {
            context.writer().println("Plugin '" + name + "' not found.");
            context.writer().flush();
            return;
        }
        LoadedPlugin p = opt.get();
        context.writer().println("Plugin: " + p.name());
        context.writer().println("Version: " + p.manifest().version());
        context.writer().println("Description: " + p.manifest().description());
        context.writer().println("Status: " + (p.enabled() ? "enabled" : "disabled"));
        context.writer().println("Directory: " + p.directory());
        if (!p.manifest().skills().isEmpty()) {
            context.writer().println("Skills: " + String.join(", ", p.manifest().skills()));
        }
        if (!p.manifest().mcpServers().isEmpty()) {
            context.writer().println("MCP Servers: " + p.manifest().mcpServers().size());
        }
        context.writer().flush();
    }
}
