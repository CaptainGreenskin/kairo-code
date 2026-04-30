package io.kairo.code.server.dto;

/**
 * Request body for updating server configuration.
 * Partial update — only non-null fields are applied.
 */
public record UpdateConfigRequest(
        String apiKey,
        String model,
        String provider,
        String baseUrl,
        String workingDir
) {}
