package io.kairo.code.server.dto;

/**
 * Server configuration response.
 */
public record ServerConfigResponse(
        String provider,
        String model,
        String workingDir,
        String baseUrl,
        boolean apiKeySet,
        Integer thinkingBudget
) {}
