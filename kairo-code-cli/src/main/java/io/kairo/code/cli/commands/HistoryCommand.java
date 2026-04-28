package io.kairo.code.cli.commands;

import io.kairo.api.agent.Agent;
import io.kairo.api.agent.AgentSnapshot;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import java.io.PrintWriter;
import java.util.List;

/**
 * Shows recent conversation turns from the current session.
 *
 * <p>Usage: {@code :history} — show last 5 turns. {@code :history N} — show last N turns (max 20).
 */
public class HistoryCommand implements SlashCommand {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    @Override
    public String name() {
        return "history";
    }

    @Override
    public String description() {
        return "Show recent conversation turns";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();

        int limit = parseLimit(args);

        Agent agent = context.agent();
        if (agent == null) {
            writer.println("No conversation history available.");
            writer.flush();
            return;
        }

        List<Msg> history;
        try {
            AgentSnapshot snapshot = agent.snapshot();
            history = snapshot.conversationHistory();
        } catch (UnsupportedOperationException e) {
            writer.println("No conversation history available.");
            writer.flush();
            return;
        }

        if (history == null || history.isEmpty()) {
            writer.println("No conversation history available.");
            writer.flush();
            return;
        }

        int start = Math.max(0, history.size() - limit);
        List<Msg> recent = history.subList(start, history.size());

        writer.println();
        for (int i = 0; i < recent.size(); i++) {
            Msg msg = recent.get(i);
            int globalIndex = start + i + 1;
            String roleLabel = formatRole(msg.role());
            String content = truncate(msg.text(), 200);

            writer.printf("[%d] %s%n", globalIndex, roleLabel);
            writer.printf("  %s%n", content);
            writer.println();
        }
        writer.flush();
    }

    private int parseLimit(String args) {
        if (args == null || args.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int n = Integer.parseInt(args.strip());
            if (n <= 0) {
                return DEFAULT_LIMIT;
            }
            return Math.min(n, MAX_LIMIT);
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    private String formatRole(MsgRole role) {
        if (role == null) {
            return "UNKNOWN";
        }
        return role.name();
    }

    private String truncate(String content, int maxLen) {
        if (content == null) {
            return "";
        }
        if (content.length() <= maxLen) {
            return content;
        }
        return content.substring(0, maxLen) + "...";
    }
}
