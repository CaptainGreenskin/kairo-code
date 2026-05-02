package io.kairo.code.server.dto;

/**
 * Request to send a user message to an agent session.
 */
public record AgentMessageRequest(
        String sessionId,
        String message,
        String imageData,        // base64 encoded, nullable
        String imageMediaType    // e.g. "image/png", nullable
) {}
