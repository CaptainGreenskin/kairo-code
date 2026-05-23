/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kairo.code.cli.commands;

import io.kairo.api.plugin.PluginInstallation;
import io.kairo.api.plugin.PluginManager;
import io.kairo.api.plugin.PluginScope;
import io.kairo.api.plugin.PluginSource;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

/**
 * Plugin management command — backed by the upstream {@link PluginManager} SPI (kairo-plugin).
 *
 * <p>Subcommands:
 *
 * <ul>
 *   <li>{@code :plugin list}                    — list installed plugins
 *   <li>{@code :plugin install <source>}        — install from source spec
 *   <li>{@code :plugin uninstall <id>}          — remove plugin entirely
 *   <li>{@code :plugin enable <id>}             — flip enabled flag on
 *   <li>{@code :plugin disable <id>}            — flip enabled flag off
 *   <li>{@code :plugin info <id>}               — show details
 *   <li>{@code :plugin reload}                  — re-scan plugin roots
 * </ul>
 *
 * <p>Source format for {@code install}:
 *
 * <pre>
 *   github:owner/repo[#ref]    e.g. github:kairo/example-plugin#v1.0
 *   npm:package[@version]      e.g. npm:@kairo/code-style@1.2.3
 *   git:&lt;url&gt;[#ref]       e.g. git:https://gitlab.com/u/r.git#main
 *   path:/absolute/or/rel      e.g. path:./my-plugin
 *   /absolute/or/rel           bare path also accepted
 * </pre>
 */
public class PluginCommand implements SlashCommand {

    private final PluginManager manager;

    public PluginCommand(PluginManager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "plugin";
    }

    @Override
    public String description() {
        return "Manage plugins (list/install/uninstall/enable/disable/info/reload)";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        if (manager == null) {
            writer.println("Plugin manager not initialized.");
            writer.flush();
            return;
        }

        String[] parts = (args == null || args.isBlank())
                ? new String[] {"list"}
                : args.trim().split("\\s+", 2);
        String sub = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (sub) {
            case "list" -> listPlugins(writer);
            case "install" -> install(arg, writer);
            case "uninstall", "remove" -> uninstall(arg, writer);
            case "enable" -> setEnabled(arg, true, writer);
            case "disable" -> setEnabled(arg, false, writer);
            case "info" -> showInfo(arg, writer);
            case "reload" -> reload(writer);
            default -> printUsage(writer);
        }
    }

    private void listPlugins(PrintWriter writer) {
        List<PluginInstallation> installs = manager.list();
        if (installs.isEmpty()) {
            writer.println("No plugins installed.");
            writer.println("Install one with: :plugin install <source>");
            writer.println("  Examples:");
            writer.println("    :plugin install github:owner/repo");
            writer.println("    :plugin install npm:@scope/package");
            writer.println("    :plugin install path:./my-plugin");
            writer.flush();
            return;
        }
        writer.printf("%-12s %-8s %-24s %-10s %s%n", "id", "state", "name", "version", "source");
        for (PluginInstallation inst : installs) {
            writer.printf(
                    "%-12s %-8s %-24s %-10s %s%n",
                    truncate(inst.id(), 12),
                    inst.enabled() ? "enabled" : "disabled",
                    truncate(inst.metadata().name(), 24),
                    truncate(inst.metadata().version(), 10),
                    inst.source().type());
        }
        writer.flush();
    }

    private void install(String arg, PrintWriter writer) {
        if (arg.isEmpty()) {
            writer.println("Usage: :plugin install <source>");
            writer.println("  github:owner/repo[#ref]");
            writer.println("  npm:package[@version]");
            writer.println("  git:<url>[#ref]");
            writer.println("  path:/absolute/or/relative");
            writer.flush();
            return;
        }
        PluginSource source;
        try {
            source = parseSource(arg);
        } catch (IllegalArgumentException e) {
            writer.println("Invalid source: " + e.getMessage());
            writer.flush();
            return;
        }
        try {
            PluginInstallation inst =
                    manager.install(source, PluginScope.USER).block(java.time.Duration.ofMinutes(5));
            if (inst != null) {
                writer.println(
                        "Installed " + inst.metadata().name() + " (" + inst.id() + "). "
                                + "Run :plugin enable " + inst.id() + " to activate.");
            } else {
                writer.println("Install returned no installation");
            }
        } catch (Exception e) {
            writer.println("Install failed: " + e.getMessage());
        }
        writer.flush();
    }

    private void uninstall(String id, PrintWriter writer) {
        if (id.isEmpty()) {
            writer.println("Usage: :plugin uninstall <id>");
            writer.flush();
            return;
        }
        try {
            manager.uninstall(id).block(java.time.Duration.ofSeconds(30));
            writer.println("Uninstalled: " + id);
        } catch (Exception e) {
            writer.println("Uninstall failed: " + e.getMessage());
        }
        writer.flush();
    }

    private void setEnabled(String id, boolean enable, PrintWriter writer) {
        if (id.isEmpty()) {
            writer.println("Usage: :plugin " + (enable ? "enable" : "disable") + " <id>");
            writer.flush();
            return;
        }
        try {
            if (enable) {
                manager.enable(id).block(java.time.Duration.ofSeconds(30));
            } else {
                manager.disable(id).block(java.time.Duration.ofSeconds(30));
            }
            writer.println("Plugin " + id + " " + (enable ? "enabled" : "disabled") + ".");
            writer.println("Run :skill reload to pick up skill changes.");
        } catch (Exception e) {
            writer.println("Operation failed: " + e.getMessage());
        }
        writer.flush();
    }

    private void showInfo(String id, PrintWriter writer) {
        if (id.isEmpty()) {
            writer.println("Usage: :plugin info <id>");
            writer.flush();
            return;
        }
        PluginInstallation inst =
                manager.list().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
        if (inst == null) {
            writer.println("Plugin '" + id + "' not found.");
            writer.flush();
            return;
        }
        writer.println("Plugin: " + inst.metadata().name());
        writer.println("ID:       " + inst.id());
        writer.println("Version:  " + inst.metadata().version());
        writer.println("Author:   " + inst.metadata().author());
        writer.println("Status:   " + (inst.enabled() ? "enabled" : "disabled"));
        writer.println("Scope:    " + inst.scope());
        writer.println("Source:   " + inst.source().type() + " — " + inst.source());
        writer.println("Root:     " + inst.rootPath());
        writer.println("Data:     " + inst.dataPath());
        writer.println("Installed: " + inst.installedAt());
        writer.flush();
    }

    private void reload(PrintWriter writer) {
        try {
            manager.reload().block(java.time.Duration.ofSeconds(30));
            writer.println("Plugin manager reloaded.");
        } catch (Exception e) {
            writer.println("Reload failed: " + e.getMessage());
        }
        writer.flush();
    }

    private void printUsage(PrintWriter writer) {
        writer.println("Usage:");
        writer.println("  :plugin list                List installed plugins");
        writer.println("  :plugin install <source>    Install from source");
        writer.println("  :plugin uninstall <id>      Remove a plugin");
        writer.println("  :plugin enable <id>         Activate a plugin");
        writer.println("  :plugin disable <id>        Deactivate without removing");
        writer.println("  :plugin info <id>           Show details");
        writer.println("  :plugin reload              Re-scan plugin roots");
        writer.flush();
    }

    private static PluginSource parseSource(String spec) {
        if (spec.startsWith("github:")) {
            String body = spec.substring("github:".length());
            String[] split = body.split("#", 2);
            return new PluginSource.GitHub(split[0], split.length > 1 ? split[1] : null, null);
        }
        if (spec.startsWith("npm:")) {
            String body = spec.substring("npm:".length());
            String pkg;
            String version = null;
            int at = body.lastIndexOf('@');
            // @scope/pkg has at index 0; only treat @ as version separator when not at start.
            if (at > 0) {
                pkg = body.substring(0, at);
                version = body.substring(at + 1);
            } else {
                pkg = body;
            }
            return new PluginSource.Npm(pkg, version, java.util.Map.of());
        }
        if (spec.startsWith("git:")) {
            String body = spec.substring("git:".length());
            String[] split = body.split("#", 2);
            return new PluginSource.GitUrl(split[0], split.length > 1 ? split[1] : null);
        }
        if (spec.startsWith("path:")) {
            return new PluginSource.LocalPath(Path.of(spec.substring("path:".length())));
        }
        // Bare path — treat any string starting with / or ./ as filesystem
        if (spec.startsWith("/") || spec.startsWith("./") || spec.startsWith("../")) {
            return new PluginSource.LocalPath(Path.of(spec));
        }
        throw new IllegalArgumentException("unknown source scheme: " + spec);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
