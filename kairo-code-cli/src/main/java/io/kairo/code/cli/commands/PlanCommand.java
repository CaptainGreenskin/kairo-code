package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.core.tool.DefaultToolExecutor;

/**
 * Toggles Plan Mode on the current session.
 *
 * <p>In Plan Mode, write/system-change tools are blocked at the {@link DefaultToolExecutor} layer,
 * so the agent can only read, search, and reason — useful for "explore before edit" workflows.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code :plan} — show current plan-mode state</li>
 *   <li>{@code :plan on} — enter plan mode</li>
 *   <li>{@code :plan off} — exit plan mode</li>
 *   <li>{@code :plan toggle} — flip the current state</li>
 * </ul>
 */
public class PlanCommand implements SlashCommand {

    @Override
    public String name() {
        return "plan";
    }

    @Override
    public String description() {
        return "Toggle Plan Mode (read-only — blocks write tools)";
    }

    @Override
    public void execute(String args, ReplContext context) {
        var writer = context.writer();
        CodeAgentSession session = context.session();
        if (session == null || session.toolExecutor() == null) {
            writer.println("Plan mode unavailable: no active session.");
            writer.flush();
            return;
        }

        DefaultToolExecutor executor = session.toolExecutor();
        String trimmed = args == null ? "" : args.strip().toLowerCase();

        boolean target;
        switch (trimmed) {
            case "" -> {
                writer.println("Plan mode: " + (executor.isPlanMode() ? "on" : "off"));
                writer.flush();
                return;
            }
            case "on", "enter", "true" -> target = true;
            case "off", "exit", "false" -> target = false;
            case "toggle" -> target = !executor.isPlanMode();
            default -> {
                writer.println("Usage: :plan [on|off|toggle]");
                writer.flush();
                return;
            }
        }

        executor.setPlanMode(target);
        writer.println(
                target
                        ? "✓ Plan mode ON — write tools blocked. Use :plan off to exit."
                        : "✓ Plan mode OFF — all tools enabled.");
        writer.flush();
    }
}
