package io.kairo.code.cli.commands;

import io.kairo.api.agent.AgentSnapshot;
import io.kairo.api.agent.SnapshotStore;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import java.io.PrintWriter;
import java.util.List;

/**
 * Save and inspect agent session snapshots.
 *
 * <p>Snapshots capture the agent's conversation history + iteration count + token usage. Pair with
 * {@code :resume <key>} to restore a snapshot into the current session.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code :snapshot save <key>} — write the current session under {@code <key>}</li>
 *   <li>{@code :snapshot load <key>} — restore a saved snapshot into the current session</li>
 *   <li>{@code :snapshot list} — show all saved keys</li>
 *   <li>{@code :snapshot delete <key>} — remove a saved snapshot</li>
 * </ul>
 */
public class SnapshotCommand implements SlashCommand {

    @Override
    public String name() {
        return "snapshot";
    }

    @Override
    public String description() {
        return "Save / list / delete session snapshots";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        SnapshotStore store = context.snapshotStore();
        if (store == null) {
            writer.println("Snapshots unavailable: no snapshot store configured.");
            writer.flush();
            return;
        }

        String trimmed = args == null ? "" : args.strip();
        if (trimmed.isEmpty()) {
            printUsage(writer);
            return;
        }

        String[] parts = trimmed.split("\\s+", 2);
        String sub = parts[0].toLowerCase();
        String rest = parts.length > 1 ? parts[1].strip() : "";

        switch (sub) {
            case "save" -> handleSave(rest, store, context, writer);
            case "load" -> handleLoad(rest, store, context, writer);
            case "list", "ls" -> handleList(store, writer);
            case "delete", "rm" -> handleDelete(rest, store, writer);
            default -> printUsage(writer);
        }
    }

    private void handleSave(
            String key, SnapshotStore store, ReplContext context, PrintWriter writer) {
        if (key.isBlank()) {
            writer.println("Usage: :snapshot save <key>");
            writer.flush();
            return;
        }
        if (context.agent() == null) {
            writer.println("Cannot snapshot: no active agent.");
            writer.flush();
            return;
        }
        AgentSnapshot snapshot;
        try {
            snapshot = context.agent().snapshot();
        } catch (UnsupportedOperationException e) {
            writer.println("Cannot snapshot: agent does not support snapshotting.");
            writer.flush();
            return;
        }
        try {
            store.save(key, snapshot).block();
            writer.printf(
                    "✓ Saved snapshot '%s' (%d messages, %d tokens).%n",
                    key, snapshot.conversationHistory().size(), snapshot.totalTokensUsed());
        } catch (Exception e) {
            writer.println("Failed to save snapshot: " + e.getMessage());
        }
        writer.flush();
    }

    private void handleLoad(
            String key, SnapshotStore store, ReplContext context, PrintWriter writer) {
        if (key.isBlank()) {
            writer.println("Usage: :snapshot load <key>");
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
            writer.println("Snapshot '" + key + "' not found.");
            writer.flush();
            return;
        }

        try {
            context.restoreFromSnapshot(snapshot);
            writer.printf(
                    "✓ Snapshot '%s' loaded (%d messages, %d tokens).%n",
                    key, snapshot.conversationHistory().size(), snapshot.totalTokensUsed());
        } catch (Exception e) {
            writer.println("Failed to restore snapshot: " + e.getMessage());
        }
        writer.flush();
    }

    private void handleList(SnapshotStore store, PrintWriter writer) {
        try {
            List<String> keys = store.listKeys(null).collectList().block();
            if (keys == null || keys.isEmpty()) {
                writer.println("No snapshots saved.");
            } else {
                writer.println();
                writer.println("Saved snapshots:");
                for (String key : keys) {
                    writer.println("  " + key);
                }
                writer.println();
                writer.println("Use :snapshot load <key> or :resume <key> to restore.");
            }
        } catch (Exception e) {
            writer.println("Failed to list snapshots: " + e.getMessage());
        }
        writer.flush();
    }

    private void handleDelete(String key, SnapshotStore store, PrintWriter writer) {
        if (key.isBlank()) {
            writer.println("Usage: :snapshot delete <key>");
            writer.flush();
            return;
        }
        try {
            store.delete(key).block();
            writer.println("✓ Deleted snapshot: " + key);
        } catch (Exception e) {
            writer.println("Failed to delete snapshot: " + e.getMessage());
        }
        writer.flush();
    }

    private void printUsage(PrintWriter writer) {
        writer.println("Usage: :snapshot <save <key>|load <key>|list|delete <key>>");
        writer.flush();
    }
}
