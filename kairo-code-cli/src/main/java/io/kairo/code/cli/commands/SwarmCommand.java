package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import java.io.PrintWriter;

/**
 * Displays Swarm execution status for the Team/Swarm workflow.
 *
 * <p>The interactive CLI does not hold an in-process {@link io.kairo.code.core.team.SwarmCoordinator}
 * (that lives in the Web server). {@link ReplContext} therefore exposes no swarm execution handle,
 * matching the {@code :team} command constraint — this command prints guidance to use the Web UI for
 * live progress until a bridge is wired.
 *
 * <p>Usage: {@code :swarm}
 */
public class SwarmCommand implements SlashCommand {

    private static final String SEPARATOR = "\u2500".repeat(42);

    @Override
    public String name() {
        return "swarm";
    }

    @Override
    public String description() {
        return "Show swarm execution status";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        writer.println();
        writer.println("Swarm Status");
        writer.println(SEPARATOR);
        writer.println("No active swarm.");
        writer.println("Use Web UI → Team → Launch Swarm to start.");
        writer.flush();
    }
}
