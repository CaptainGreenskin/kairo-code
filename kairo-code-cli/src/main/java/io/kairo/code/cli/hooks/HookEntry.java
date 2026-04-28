package io.kairo.code.cli.hooks;

/**
 * A single hook entry parsed from {@code ~/.kairo-code/hooks.json}.
 *
 * @param matcher tool name to match, or {@code "*"} for all tools
 * @param command shell command to execute (supports {@code {{tool_name}}} / {@code {{tool_input}}} placeholders)
 */
public record HookEntry(String matcher, String command) {

    /** Whether this entry matches the given tool name. */
    public boolean matches(String toolName) {
        return "*".equals(matcher) || matcher.equals(toolName);
    }
}
