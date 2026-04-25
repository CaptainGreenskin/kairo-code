package io.kairo.code.core;

import io.kairo.api.agent.Agent;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import java.util.Set;

/**
 * Bundle of agent + tool runtime state for a single REPL session.
 *
 * <p>Holds references the slash commands need to mutate state without recreating the whole agent:
 * the {@link DefaultToolExecutor} for plan-mode toggling, the {@link DefaultToolRegistry} for tool
 * lookup, and the set of skill names currently injected into the system prompt.
 *
 * @param agent the live agent
 * @param toolExecutor the tool executor (for plan mode toggle)
 * @param toolRegistry the tool registry
 * @param loadedSkills immutable set of skill names currently active in the system prompt
 */
public record CodeAgentSession(
        Agent agent,
        DefaultToolExecutor toolExecutor,
        DefaultToolRegistry toolRegistry,
        Set<String> loadedSkills) {

    public CodeAgentSession {
        if (agent == null) throw new IllegalArgumentException("agent must not be null");
        if (toolExecutor == null) throw new IllegalArgumentException("toolExecutor must not be null");
        if (toolRegistry == null) throw new IllegalArgumentException("toolRegistry must not be null");
        loadedSkills = loadedSkills == null ? Set.of() : Set.copyOf(loadedSkills);
    }
}
