package io.kairo.code.server.dto;

/**
 * Request to create a new agent session.
 */
public record CreateSessionRequest(
        String workingDir,
        String provider,
        String model,
        String apiKey
) {
    public CreateSessionRequest {
        if (workingDir == null || workingDir.isBlank()) {
            workingDir = System.getProperty("user.home") + "/kairo-workspace";
        }
        if (model == null || model.isBlank()) {
            model = "gpt-4o";
        }
    }
}
