package io.kairo.code.core;

import io.kairo.api.agent.Agent;
import io.kairo.code.core.stats.ToolUsageTracker;
import io.kairo.code.core.stats.TurnMetricsCollector;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import io.kairo.mcp.McpClientRegistry;
import java.util.Set;

/**
 * Bundle of agent + tool runtime state for a single REPL session.
 *
 * <p>Holds references the slash commands need to mutate state without recreating the whole agent:
 * the {@link DefaultToolExecutor} for plan-mode toggling, the {@link DefaultToolRegistry} for tool
 * lookup, the set of skill names currently injected into the system prompt, and the
 * {@link ToolUsageTracker} for :stats command.
 *
 * @param agent the live agent
 * @param toolExecutor the tool executor (for plan mode toggle)
 * @param toolRegistry the tool registry
 * @param loadedSkills immutable set of skill names currently active in the system prompt
 * @param mcpRegistry MCP client registry for querying server info (null if no MCP servers configured)
 * @param toolUsageTracker tracks per-tool call counts, success rates, and durations
 * @param turnMetricsCollector tracks per-turn tool call counts, success rates, and durations
 */
public record CodeAgentSession(
        Agent agent,
        DefaultToolExecutor toolExecutor,
        DefaultToolRegistry toolRegistry,
        Set<String> loadedSkills,
        McpClientRegistry mcpRegistry,
        ToolUsageTracker toolUsageTracker,
        TurnMetricsCollector turnMetricsCollector) {

    public CodeAgentSession(
            Agent agent,
            DefaultToolExecutor toolExecutor,
            DefaultToolRegistry toolRegistry,
            Set<String> loadedSkills) {
        this(agent, toolExecutor, toolRegistry, loadedSkills, null,
                new ToolUsageTracker(), new TurnMetricsCollector());
    }

    public CodeAgentSession(
            Agent agent,
            DefaultToolExecutor toolExecutor,
            DefaultToolRegistry toolRegistry,
            Set<String> loadedSkills,
            McpClientRegistry mcpRegistry) {
        this(agent, toolExecutor, toolRegistry, loadedSkills, mcpRegistry,
                new ToolUsageTracker(), new TurnMetricsCollector());
    }

    public CodeAgentSession {
        if (agent == null) throw new IllegalArgumentException("agent must not be null");
        if (toolExecutor == null) throw new IllegalArgumentException("toolExecutor must not be null");
        if (toolRegistry == null) throw new IllegalArgumentException("toolRegistry must not be null");
        loadedSkills = loadedSkills == null ? Set.of() : Set.copyOf(loadedSkills);
        if (toolUsageTracker == null) toolUsageTracker = new ToolUsageTracker();
        if (turnMetricsCollector == null) turnMetricsCollector = new TurnMetricsCollector();
    }
}
