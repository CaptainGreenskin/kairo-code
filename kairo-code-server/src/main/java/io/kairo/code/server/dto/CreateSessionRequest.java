package io.kairo.code.server.dto;

/**
 * Request to create a new agent session.
 * All fields except workingDir are optional — AgentController falls back to serverProperties.
 */
public record CreateSessionRequest(
        String workingDir,
        String provider,
        String model,
        String apiKey
) {}
