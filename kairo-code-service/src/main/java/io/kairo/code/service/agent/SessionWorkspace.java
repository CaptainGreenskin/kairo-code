package io.kairo.code.service.agent;

import io.kairo.api.workspace.Workspace;
import io.kairo.api.workspace.WorkspaceKind;
import java.nio.file.Path;
import java.util.Map;

/**
 * Lightweight Workspace implementation backed by a session's working directory path.
 */
record SessionWorkspace(String dir) implements Workspace {

    @Override
    public String id() {
        return "session-" + dir.hashCode();
    }

    @Override
    public Path root() {
        return Path.of(dir);
    }

    @Override
    public WorkspaceKind kind() {
        return WorkspaceKind.LOCAL;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of();
    }
}
