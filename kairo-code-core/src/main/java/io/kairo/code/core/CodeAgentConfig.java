package io.kairo.code.core;

/**
 * Configuration for creating a CodeAgent.
 *
 * @param apiKey       the API key for the model provider (required)
 * @param baseUrl      the base URL for the OpenAI-compatible API (default: "https://api.openai.com")
 * @param modelName    the model name to use (default: "gpt-4o")
 * @param maxIterations maximum ReAct loop iterations (default: 50)
 * @param workingDir   the working directory for file/exec tools (nullable)
 */
public record CodeAgentConfig(
        String apiKey,
        String baseUrl,
        String modelName,
        int maxIterations,
        String workingDir
) {
    public CodeAgentConfig {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey is required");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com";
        }
        if (modelName == null || modelName.isBlank()) {
            modelName = "gpt-4o";
        }
        if (maxIterations <= 0) {
            maxIterations = 50;
        }
    }
}
