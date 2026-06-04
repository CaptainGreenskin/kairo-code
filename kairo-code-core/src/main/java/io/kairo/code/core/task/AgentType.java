package io.kairo.code.core.task;

import io.kairo.multiagent.subagent.SubagentType;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thin bridge to {@link SubagentType} from kairo-multi-agent.
 * Kept for backward compatibility with existing kairo-code callers.
 * Delegates all logic to the framework-level {@link SubagentType}.
 */
public enum AgentType {

    GENERAL_PURPOSE(
            "general-purpose",
            "General-purpose agent for researching complex questions, searching code, and executing multi-step tasks.",
            null, // null = all tools allowed (except task itself)
            ""    // no additional system prompt
    ),

    EXPLORE(
            "explore",
            "Fast read-only search agent for locating code. Use it to find files, grep for symbols, or answer 'where is X defined'. Do NOT use for code modifications.",
            Set.of("bash", "read", "grep", "glob", "tree", "batch_read", "diff", "json_query", "git"),
            "You are a read-only exploration agent. Your job is to search and analyze code — never modify files. "
                    + "Be thorough but concise. Return findings as structured data the parent agent can act on."
    ),

    PLAN(
            "plan",
            "Software architect agent for designing implementation plans. Returns step-by-step plans, identifies critical files, and considers architectural trade-offs.",
            Set.of("bash", "read", "grep", "glob", "tree", "batch_read", "diff", "json_query", "git"),
            "You are a planning agent. Analyze the codebase, identify the right approach, and produce a clear "
                    + "step-by-step implementation plan. Do NOT modify any files — only read and analyze. "
                    + "Your output should be a structured plan the parent agent can execute."
    ),

    CODER(
            "coder",
            "Implementation agent with full read/write capabilities. Use for writing code, applying changes, running tests.",
            null, // all tools
            "You are a coding agent. Implement the task described in the prompt. Write clean, correct code. "
                    + "Run relevant tests after making changes to verify correctness."
    ),

    REVIEWER(
            "reviewer",
            "Code review agent. Reads code and git diffs, identifies bugs, suggests improvements. Read-only.",
            Set.of("bash", "read", "grep", "glob", "tree", "batch_read", "diff", "json_query", "git"),
            "You are a code reviewer. Analyze the code for correctness bugs, security issues, performance problems, "
                    + "and code quality. Be specific — cite file paths and line numbers. Do NOT modify files."
    );

    private final String id;
    private final String description;
    private final Set<String> allowedTools;
    private final String systemPromptPrefix;

    AgentType(String id, String description, Set<String> allowedTools, String systemPromptPrefix) {
        this.id = id;
        this.description = description;
        this.allowedTools = allowedTools;
        this.systemPromptPrefix = systemPromptPrefix;
    }

    public String id() { return id; }
    public String description() { return description; }
    /** Null means all tools are allowed (except the task tool itself). */
    public Set<String> allowedTools() { return allowedTools; }
    public String systemPromptPrefix() { return systemPromptPrefix; }

    private static final Map<String, AgentType> BY_ID;
    static {
        var map = new java.util.HashMap<String, AgentType>();
        for (AgentType t : values()) {
            map.put(t.id, t);
        }
        // Short aliases
        map.put("explore", EXPLORE);
        map.put("plan", PLAN);
        map.put("coder", CODER);
        map.put("reviewer", REVIEWER);
        map.put("general", GENERAL_PURPOSE);
        BY_ID = Map.copyOf(map);
    }

    /**
     * Resolve an agent type by ID. Returns null if not found.
     */
    public static AgentType resolve(String id) {
        if (id == null || id.isBlank()) return GENERAL_PURPOSE;
        return BY_ID.get(id.toLowerCase().trim());
    }

    /**
     * List all available agent type IDs for error messages.
     */
    public static List<String> availableIds() {
        return List.of("general-purpose", "explore", "plan", "coder", "reviewer");
    }
}
