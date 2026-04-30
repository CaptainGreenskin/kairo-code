package io.kairo.code.server.dto;

/**
 * Summary info about an active session.
 */
public record SessionInfo(
        String sessionId,
        String workingDir,
        String model,
        long createdAt,
        boolean running
) {}
