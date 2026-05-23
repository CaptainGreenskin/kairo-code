package io.kairo.code.core;

import io.kairo.code.core.config.ProviderRegistry;
import io.kairo.code.core.mcp.McpConfig;

/**
 * Configuration for creating a CodeAgent.
 *
 * @param apiKey       the API key for the model provider (required)
 * @param baseUrl      the base URL for the OpenAI-compatible API. Falls back to the
 *                     {@link ProviderRegistry} default for the given {@code modelName}
 *                     when blank — silent OpenAI default removed because it was sending
 *                     GLM / Claude / Qwen requests to api.openai.com.
 * @param modelName    the model name to use (default: "gpt-4o")
 * @param maxIterations maximum ReAct loop iterations (default: unlimited — the agent stops when
 *   the model returns no tool calls; loop runaway is caught by LoopDetector, not by this cap)
 * @param workingDir   the working directory for file/exec tools (nullable)
 * @param mcpConfig    MCP server config from ~/.kairo-code/mcp.json (nullable)
 * @param toolBudgetForce max tool calls before hard stop (0 = use default from ToolBudgetHook)
 * @param repetitiveToolThreshold consecutive turns threshold for RepetitiveToolHook (0 = use default)
 * @param thinkingBudget the Anthropic extended thinking budget in tokens (null = disabled, 0 = use default)
 */
public record CodeAgentConfig(
        String apiKey,
        String baseUrl,
        String modelName,
        int maxIterations,
        String workingDir,
        McpConfig mcpConfig,
        int toolBudgetForce,
        int repetitiveToolThreshold,
        Integer thinkingBudget
) {
    public CodeAgentConfig(String apiKey, String baseUrl, String modelName,
                           int maxIterations, String workingDir, McpConfig mcpConfig,
                           int toolBudgetForce, int repetitiveToolThreshold) {
        this(apiKey, baseUrl, modelName, maxIterations, workingDir, mcpConfig,
             toolBudgetForce, repetitiveToolThreshold, null);
    }

    public CodeAgentConfig {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey is required");
        }
        if (modelName == null || modelName.isBlank()) {
            modelName = "gpt-4o";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            // Was hardcoded "https://api.openai.com" — meant any layer that left
            // baseUrl blank for a glm/claude/qwen model silently sent the request
            // to the OpenAI endpoint, with `model not found` surfacing as the user
            // bug. Infer from the model name family so the default actually
            // matches the model. Truly unknown model name still falls back to
            // OpenAI (last-resort, not silent).
            baseUrl = inferBaseUrlFromModelName(modelName);
        }
        if (maxIterations <= 0) {
            // Mirrors opencode / claude-code: no hard cap. The model decides when to stop
            // (no tool_calls = done); LoopDetector catches doom loops, ContextCompactionEngine
            // handles overflow, per-tool timeouts catch stuck operations.
            maxIterations = Integer.MAX_VALUE;
        }
        if (toolBudgetForce < 0) {
            toolBudgetForce = 0;
        }
        if (repetitiveToolThreshold < 0) {
            repetitiveToolThreshold = 0;
        }
        if (thinkingBudget != null && thinkingBudget < 0) {
            thinkingBudget = 0;
        }
    }

    private static String inferBaseUrlFromModelName(String modelName) {
        String lower = modelName.toLowerCase();
        if (lower.startsWith("claude")) return ProviderRegistry.resolveBaseUrl("anthropic");
        if (lower.startsWith("glm")) return ProviderRegistry.resolveBaseUrl("glm");
        if (lower.startsWith("qwen")) return ProviderRegistry.resolveBaseUrl("qianwen");
        return ProviderRegistry.resolveBaseUrl("openai");
    }
}
