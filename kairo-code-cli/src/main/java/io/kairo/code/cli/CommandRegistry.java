package io.kairo.code.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of slash commands available in the REPL.
 *
 * <p>Commands can be invoked with either {@code :name} or {@code /name} prefix.
 * The registry is designed to be extensible — new commands can be registered at any time.
 */
public class CommandRegistry {

    private final Map<String, SlashCommand> commands = new LinkedHashMap<>();

    /** Register a slash command. Overwrites any existing command with the same name. */
    public void register(SlashCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        commands.put(command.name().toLowerCase(), command);
    }

    /**
     * Resolve user input to a command.
     *
     * <p>Accepts both {@code :name} and {@code /name} prefixes.
     *
     * @param input the raw user input (e.g., ":help", "/exit")
     * @return the matching command, or empty if not a command or not found
     */
    public Optional<SlashCommand> resolve(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String trimmed = input.trim();
        if (!trimmed.startsWith(":") && !trimmed.startsWith("/")) {
            return Optional.empty();
        }
        // Extract command name (first token after prefix)
        String withoutPrefix = trimmed.substring(1);
        String commandName = withoutPrefix.split("\\s+", 2)[0].toLowerCase();
        return Optional.ofNullable(commands.get(commandName));
    }

    /**
     * Extract the arguments portion of a command input.
     *
     * @param input the raw user input (e.g., ":model gpt-4o")
     * @return the args after the command name, or empty string
     */
    public static String extractArgs(String input) {
        if (input == null) {
            return "";
        }
        String trimmed = input.trim();
        if (trimmed.startsWith(":") || trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        String[] parts = trimmed.split("\\s+", 2);
        return parts.length > 1 ? parts[1] : "";
    }

    /** Return all registered commands (in registration order). */
    public List<SlashCommand> allCommands() {
        return Collections.unmodifiableList(new ArrayList<>(commands.values()));
    }

    /** Return all registered command names. */
    public List<String> allCommandNames() {
        return List.copyOf(commands.keySet());
    }
}
