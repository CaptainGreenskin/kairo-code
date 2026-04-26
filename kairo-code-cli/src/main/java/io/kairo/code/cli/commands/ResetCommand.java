package io.kairo.code.cli.commands;

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.core.ConsoleApprovalHandler;

/**
 * Completely resets the agent session: clears conversation history, unloads all skills,
 * and resets tool-use approvals — equivalent to a fresh start.
 *
 * <p>Usage: {@code :reset}
 *
 * <p>Differs from {@code :clear} in that it also unloads any loaded skills.
 */
public class ResetCommand implements SlashCommand {

    @Override
    public String name() {
        return "reset";
    }

    @Override
    public String description() {
        return "Completely reset session (clears history and unloads skills)";
    }

    @Override
    public void execute(String args, ReplContext context) {
        context.clearSkills();
        ConsoleApprovalHandler handler = context.approvalHandler();
        if (handler != null) {
            handler.resetApprovals();
        }
        context.writer().println("✓ Session reset.");
        context.writer().flush();
    }
}
