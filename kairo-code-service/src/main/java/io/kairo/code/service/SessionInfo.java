package io.kairo.code.service;

/**
 * Summary info about an active session.
 *
 * <p>{@code workspaceId} is nullable for legacy callers (tests / pre-M112 sessions) that haven't
 * been migrated to the workspace-aware API yet.
 */
public record SessionInfo(
        String sessionId,
        String workingDir,
        String model,
        long createdAt,
        boolean running,
        String workspaceId,
        boolean isGit
) {
    /** Legacy 5-arg constructor (no workspace, no git flag). */
    public SessionInfo(String sessionId, String workingDir, String model,
                       long createdAt, boolean running) {
        this(sessionId, workingDir, model, createdAt, running, null, false);
    }

    /** Legacy 6-arg constructor (no git flag). */
    public SessionInfo(String sessionId, String workingDir, String model,
                       long createdAt, boolean running, String workspaceId) {
        this(sessionId, workingDir, model, createdAt, running, workspaceId, false);
    }
}
