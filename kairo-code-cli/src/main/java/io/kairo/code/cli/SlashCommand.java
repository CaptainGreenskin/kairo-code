package io.kairo.code.cli;

/**
 * A slash command that can be registered in the REPL command registry.
 *
 * <p>Commands are triggered by typing {@code :name} or {@code /name} at the prompt.
 */
public interface SlashCommand {

    /** The command name without prefix (e.g., "help", "clear"). */
    String name();

    /** A short description shown in the help listing. */
    String description();

    /**
     * Execute this command.
     *
     * @param args the remaining text after the command name (may be empty)
     * @param context the REPL context providing access to agent, writer, etc.
     */
    void execute(String args, ReplContext context);
}
