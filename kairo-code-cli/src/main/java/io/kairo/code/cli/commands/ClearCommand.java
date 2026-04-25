package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.core.ConsoleApprovalHandler;

/**
 * Resets conversation context by recreating the agent.
 *
 * <p>Usage: {@code :clear}
 */
public class ClearCommand implements SlashCommand {

    @Override
    public String name() {
        return "clear";
    }

    @Override
    public String description() {
        return "Reset conversation context";
    }

    @Override
    public void execute(String args, ReplContext context) {
        context.resetAgent();
        ConsoleApprovalHandler handler = context.approvalHandler();
        if (handler != null) {
            handler.resetApprovals();
        }
        context.writer().println("✓ Conversation cleared.");
        context.writer().flush();
    }
}
