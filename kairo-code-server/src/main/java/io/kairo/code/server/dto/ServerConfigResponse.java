package io.kairo.code.server.dto;

/**
 * Server configuration response — user-global only.
 *
 * <p>Workspace fields ({@code workingDir}, {@code useWorktree}) live on the
 * {@link io.kairo.code.server.config.WorkspaceConfig} entity served by
 * {@code /api/workspaces} and are not part of this payload.
 */
public record ServerConfigResponse(
        String provider,
        String model,
        String baseUrl,
        boolean apiKeySet,
        Integer thinkingBudget
) {}
