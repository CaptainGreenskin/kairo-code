package io.kairo.code.server.dto;

/**
 * Request to bind to an existing session and restore its history.
 */
public record BindSessionRequest(
        String sessionId
) {}
