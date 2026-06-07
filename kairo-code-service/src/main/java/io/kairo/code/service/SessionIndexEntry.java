package io.kairo.code.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionIndexEntry(
        String sessionId,
        String name,
        String workspaceId,
        String workingDir,
        String model,
        String status,
        long createdAt,
        long updatedAt,
        int messageCount,
        boolean hasSnapshot
) {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_IDLE = "idle";
    public static final String STATUS_ARCHIVED = "archived";

    public SessionIndexEntry withStatus(String newStatus) {
        return new SessionIndexEntry(sessionId, name, workspaceId, workingDir, model,
                newStatus, createdAt, System.currentTimeMillis(), messageCount, hasSnapshot);
    }

    public SessionIndexEntry withName(String newName) {
        return new SessionIndexEntry(sessionId, newName, workspaceId, workingDir, model,
                status, createdAt, System.currentTimeMillis(), messageCount, hasSnapshot);
    }

    public SessionIndexEntry withSnapshot(boolean snapshot, int msgCount) {
        return new SessionIndexEntry(sessionId, name, workspaceId, workingDir, model,
                status, createdAt, System.currentTimeMillis(), msgCount, snapshot);
    }
}
