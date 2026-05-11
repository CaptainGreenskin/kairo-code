package io.kairo.code.server.dto;

/**
 * Request body for updating user-global server configuration.
 * Partial update — only non-null fields are applied.
 *
 * <p>Workspace fields are managed via {@code /api/workspaces} and are not part of this payload.
 */
public record UpdateConfigRequest(
        String apiKey,
        String model,
        String provider,
        String baseUrl,
        Integer thinkingBudget
) {}
