package io.kairo.code.server.config;

/**
 * First-class workspace entity, persisted to ~/.kairo-code/workspaces.json.
 * One workspace = one workingDir; sessions belong to a workspace.
 */
public record WorkspaceConfig(
        String id,
        String name,
        String workingDir,
        boolean useWorktree,
        long createdAt
) {}
