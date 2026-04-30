package io.kairo.code.cli.commands;

import io.kairo.api.agent.AgentSnapshot;
import io.kairo.api.agent.AgentState;
import io.kairo.api.agent.SnapshotStore;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.core.session.SessionTurn;
import io.kairo.code.core.session.SessionWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Restore a previously saved snapshot or JSONL session into the current session.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code :resume} — list available JSONL sessions</li>
 *   <li>{@code :resume <session-id>} — resume a JSONL session by ID</li>
 *   <li>{@code :resume snapshot:<key>} — restore a snapshot (legacy behaviour)</li>
 * </ul>
 */
public class ResumeCommand implements SlashCommand {

    private static final String KAIRO_CODE_DIR = ".kairo-code";
    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    public String name() {
        return "resume";
    }

    @Override
    public String description() {
        return "Resume a saved session or snapshot";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        String key = args == null ? "" : args.strip();

        // Legacy snapshot path: :resume snapshot:<key>
        if (key.startsWith("snapshot:")) {
            resumeSnapshot(key.substring("snapshot:".length()).strip(), context);
            return;
        }

        // No args: list available sessions
        if (key.isBlank()) {
            listSessions(context);
            return;
        }

        // Session ID provided: resume from JSONL file
        resumeSession(key, context);
    }

    private void resumeSession(String sessionId, ReplContext context) {
        PrintWriter writer = context.writer();
        Path sessionsDir = resolveSessionsDir(context);
        Path sessionFile = sessionsDir.resolve(sessionId + ".jsonl");

        if (!Files.exists(sessionFile)) {
            writer.println("Session not found: " + sessionId);
            writer.println("Use :resume without arguments to list available sessions.");
            writer.flush();
            return;
        }

        SessionWriter sessionReader = new SessionWriter(sessionFile);
        List<SessionTurn> turns = sessionReader.readSession();
        if (turns.isEmpty()) {
            writer.println("Session " + sessionId + " is empty.");
            writer.flush();
            return;
        }

        // Rebuild conversation history from turns
        List<Msg> messages = new ArrayList<>();
        for (SessionTurn turn : turns) {
            MsgRole role = "assistant".equals(turn.role()) ? MsgRole.ASSISTANT : MsgRole.USER;
            messages.add(Msg.of(role, turn.content()));
        }

        // Restore via snapshot mechanism
        AgentSnapshot snapshot = new AgentSnapshot(
                "session-" + sessionId,
                "session-" + sessionId,
                AgentState.IDLE,
                0, 0L,
                messages,
                Map.of(),
                Instant.now());
        try {
            context.restoreFromSnapshot(snapshot);
            writer.printf("✓ Resumed session %s: %d turns loaded.%n", sessionId, turns.size());
        } catch (Exception e) {
            writer.println("Failed to restore session: " + e.getMessage());
        }
        writer.flush();
    }

    private void listSessions(ReplContext context) {
        PrintWriter writer = context.writer();
        Path sessionsDir = resolveSessionsDir(context);

        if (!Files.isDirectory(sessionsDir)) {
            writer.println("No sessions found.");
            writer.flush();
            return;
        }

        List<SessionInfo> sessions = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionsDir, "*.jsonl")) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                String id = fileName.substring(0, fileName.length() - ".jsonl".length());
                long lineCount = 0;
                try {
                    lineCount = Files.lines(file).filter(l -> !l.isBlank()).count();
                } catch (IOException ignored) {}
                Instant lastModified;
                try {
                    lastModified = Files.getLastModifiedTime(file).toInstant();
                } catch (IOException e) {
                    lastModified = Instant.EPOCH;
                }
                sessions.add(new SessionInfo(id, lineCount, lastModified));
            }
        } catch (IOException e) {
            writer.println("Error listing sessions: " + e.getMessage());
            writer.flush();
            return;
        }

        if (sessions.isEmpty()) {
            writer.println("No sessions found.");
            writer.flush();
            return;
        }

        // Sort by most recent first
        sessions.sort(Comparator.comparing(SessionInfo::lastModified).reversed());

        writer.println();
        writer.printf("  %-10s  %6s  %-20s%n", "Session ID", "Turns", "Last Modified");
        writer.println("  " + "\u2500".repeat(42));
        for (SessionInfo info : sessions) {
            writer.printf("  %-10s  %6d  %-20s%n",
                    info.id(), info.turns(), DT_FMT.format(info.lastModified()));
        }
        writer.println();
        writer.println("Use :resume <session-id> to restore a session.");
        writer.flush();
    }

    private void resumeSnapshot(String key, ReplContext context) {
        PrintWriter writer = context.writer();
        SnapshotStore store = context.snapshotStore();
        if (store == null) {
            writer.println("Resume unavailable: no snapshot store configured.");
            writer.flush();
            return;
        }

        if (key.isBlank()) {
            writer.println("Usage: :resume snapshot:<key>");
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
                    "✓ Resumed from snapshot '%s' (%d messages, %d tokens).%n",
                    key, snapshot.conversationHistory().size(), snapshot.totalTokensUsed());
        } catch (Exception e) {
            writer.println("Failed to restore snapshot: " + e.getMessage());
        }
        writer.flush();
    }

    private Path resolveSessionsDir(ReplContext context) {
        String workingDir = context.config() != null ? context.config().workingDir() : null;
        if (workingDir != null && !workingDir.isBlank()) {
            return Path.of(workingDir, KAIRO_CODE_DIR, "sessions");
        }
        return Path.of(System.getProperty("user.home"), KAIRO_CODE_DIR, "sessions");
    }

    private record SessionInfo(String id, long turns, Instant lastModified) {}
}
