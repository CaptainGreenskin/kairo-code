package io.kairo.code.service;

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
