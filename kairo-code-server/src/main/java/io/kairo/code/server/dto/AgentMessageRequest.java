package io.kairo.code.server.dto;

/**
 * Request to send a user message to an agent session.
 */
public record AgentMessageRequest(
        String sessionId,
        String message
) {}
