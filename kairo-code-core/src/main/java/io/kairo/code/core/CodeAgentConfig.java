package io.kairo.code.core;

import io.kairo.code.core.mcp.McpConfig;

/**
 * Configuration for creating a CodeAgent.
 *
 * @param apiKey       the API key for the model provider (required)
 * @param baseUrl      the base URL for the OpenAI-compatible API (default: "https://api.openai.com")
 * @param modelName    the model name to use (default: "gpt-4o")
 * @param maxIterations maximum ReAct loop iterations (default: 50)
 * @param workingDir   the working directory for file/exec tools (nullable)
 * @param mcpConfig    MCP server config from ~/.kairo-code/mcp.json (nullable)
 * @param toolBudgetForce max tool calls before hard stop (0 = use default from ToolBudgetHook)
 * @param repetitiveToolThreshold consecutive turns threshold for RepetitiveToolHook (0 = use default)
 */
public record CodeAgentConfig(
        String apiKey,
        String baseUrl,
        String modelName,
        int maxIterations,
        String workingDir,
        McpConfig mcpConfig,
        int toolBudgetForce,
        int repetitiveToolThreshold
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
        if (toolBudgetForce < 0) {
            toolBudgetForce = 0;
        }
        if (repetitiveToolThreshold < 0) {
            repetitiveToolThreshold = 0;
        }
    }
}
