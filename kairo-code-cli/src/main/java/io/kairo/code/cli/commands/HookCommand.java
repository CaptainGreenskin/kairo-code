package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.cli.hooks.HookEntry;
import io.kairo.code.cli.hooks.HooksConfig;
import java.util.List;
import java.util.Map;

/**
 * REPL command for managing shell hooks.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code :hook list} — list all configured shell hooks</li>
 * </ul>
 */
public class HookCommand implements SlashCommand {

    @Override
    public String name() {
        return "hook";
    }

    @Override
    public String description() {
        return "Manage shell hooks";
    }

    @Override
    public void execute(String args, ReplContext context) {
        String sub = args == null ? "" : args.trim();

        if ("list".equals(sub)) {
            listHooks(context);
        } else if (sub.isEmpty()) {
            listHooks(context);
        } else {
            context.writer().println("Unknown hook subcommand: " + sub);
            context.writer().println("Usage: :hook list");
        }
        context.writer().flush();
    }

    private void listHooks(ReplContext context) {
        HooksConfig config = context.hooksConfig();
        var writer = context.writer();

        if (config.isEmpty()) {
            writer.println("No shell hooks configured.");
            writer.println("Configure hooks in ~/.kairo-code/hooks.json");
            return;
        }

        writer.println();
        writer.println("Configured shell hooks:");
        for (Map.Entry<String, List<HookEntry>> entry : config.getAll().entrySet()) {
            String event = entry.getKey();
            for (HookEntry hook : entry.getValue()) {
                writer.printf("  %-15s matcher=%-10s command=%s%n",
                        event, hook.matcher(), hook.command());
            }
        }
        writer.println();
    }
}
