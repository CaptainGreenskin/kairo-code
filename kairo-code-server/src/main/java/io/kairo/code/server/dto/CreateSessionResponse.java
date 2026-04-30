package io.kairo.code.server.dto;

/**
 * Response for session creation.
 */
public record CreateSessionResponse(
        String sessionId,
        String workingDir,
        String model
) {}
