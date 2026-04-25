package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;

/**
 * Exits the REPL loop.
 *
 * <p>Usage: {@code :exit} or {@code /exit}
 */
public class ExitCommand implements SlashCommand {

    @Override
    public String name() {
        return "exit";
    }

    @Override
    public String description() {
        return "Exit the REPL";
    }

    @Override
    public void execute(String args, ReplContext context) {
        context.writer().println("Goodbye!");
        context.writer().flush();
        context.requestExit();
    }
}
