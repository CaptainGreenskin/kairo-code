package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;

/**
 * Lists all registered slash commands with their descriptions.
 */
public class HelpCommand implements SlashCommand {

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "List available commands";
    }

    @Override
    public void execute(String args, ReplContext context) {
        var writer = context.writer();
        writer.println();
        writer.println("Available commands:");
        for (SlashCommand cmd : context.commandRegistry().allCommands()) {
            writer.printf("  :%-12s %s%n", cmd.name(), cmd.description());
        }
        writer.println();
        writer.flush();
    }
}
