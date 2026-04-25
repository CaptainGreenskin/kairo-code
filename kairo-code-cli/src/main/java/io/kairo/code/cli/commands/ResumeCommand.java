package io.kairo.code.cli.commands;

import io.kairo.api.agent.AgentSnapshot;
import io.kairo.api.agent.SnapshotStore;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import java.io.PrintWriter;

/**
 * Restore a previously saved snapshot into the current session.
 *
 * <p>Usage: {@code :resume <key>} — load the snapshot saved under {@code <key>} and replace the
 * current session's agent state (conversation history, iteration count, etc.) with it.
 */
public class ResumeCommand implements SlashCommand {

    @Override
    public String name() {
        return "resume";
    }

    @Override
    public String description() {
        return "Restore a saved snapshot into the current session";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        SnapshotStore store = context.snapshotStore();
        if (store == null) {
            writer.println("Resume unavailable: no snapshot store configured.");
            writer.flush();
            return;
        }

        String key = args == null ? "" : args.strip();
        if (key.isBlank()) {
            writer.println("Usage: :resume <key>");
            writer.flush();
            return;
        }

        AgentSnapshot snapshot;
        try {
            snapshot = store.load(key).block();
        } catch (Exception e) {
            writer.println("Failed to load snapshot: " + e.getMessage());
            writer.flush();
            return;
        }
        if (snapshot == null) {
            writer.println("Snapshot not found: " + key);
            writer.flush();
            return;
        }

        try {
            context.restoreFromSnapshot(snapshot);
            writer.printf(
                    "✓ Resumed from '%s' (%d messages, %d tokens).%n",
                    key, snapshot.conversationHistory().size(), snapshot.totalTokensUsed());
        } catch (Exception e) {
            writer.println("Failed to restore snapshot: " + e.getMessage());
        }
        writer.flush();
    }
}
