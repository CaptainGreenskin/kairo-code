package io.kairo.code.cli.commands;

import io.kairo.api.agent.AgentSnapshot;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import java.io.PrintWriter;
import java.util.List;

public class HistoryCommand implements SlashCommand {

    private static final int MAX_TEXT_LENGTH = 120;

    @Override
    public String name() {
        return "history";
    }

    @Override
    public String description() {
        return "Show conversation history for this session";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        List<Msg> history;
        try {
            AgentSnapshot snapshot = context.agent().snapshot();
            history = snapshot.conversationHistory();
        } catch (UnsupportedOperationException e) {
            writer.println("Conversation history not available for this agent.");
            writer.flush();
            return;
        }

        if (history.isEmpty()) {
            writer.println("No conversation history.");
            writer.flush();
            return;
        }

        writer.println();
        writer.printf("Conversation history (%d messages):%n", history.size());
        writer.println("─────────────────────────────────────");
        for (int i = 0; i < history.size(); i++) {
            Msg msg = history.get(i);
            String roleLabel = msg.role() == MsgRole.USER ? "User" : "Assistant";
            String text = msg.text();
            if (text.length() > MAX_TEXT_LENGTH) {
                text = text.substring(0, MAX_TEXT_LENGTH - 3) + "...";
            }
            writer.printf("[%d] %s: %s%n", i + 1, roleLabel, text);
        }
        writer.println();
        writer.flush();
    }
}
