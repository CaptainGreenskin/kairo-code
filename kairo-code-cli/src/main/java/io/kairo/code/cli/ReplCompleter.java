package io.kairo.code.cli;

import io.kairo.api.skill.SkillCategory;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

/**
 * JLine completer for the kairo-code REPL.
 *
 * <p>Provides context-sensitive tab completion:
 * <ul>
 *   <li>Top-level: all registered slash commands prefixed with {@code :}</li>
 *   <li>{@code :skill} subcommands: list, loaded, load, unload, info</li>
 *   <li>{@code :skill load/unload/info <TAB>}: dynamic skill names from SkillRegistry</li>
 *   <li>{@code :skill list <TAB>}: SkillCategory enum values</li>
 * </ul>
 */
public class ReplCompleter implements Completer {

    private static final List<String> SKILL_SUBCOMMANDS =
            List.of("list", "loaded", "load", "unload", "info");

    private static final List<String> MEMORY_SUBCOMMANDS =
            List.of("list", "search", "add", "delete", "info", "clear");

    private static final List<String> MEMORY_SCOPES =
            List.of("session", "agent", "global");

    private static final List<String> SKILL_CATEGORIES =
            Arrays.stream(SkillCategory.values())
                    .map(c -> c.name().toLowerCase())
                    .toList();

    private final CommandRegistry commandRegistry;
    private final Supplier<SkillRegistry> skillRegistrySupplier;

    /**
     * @param commandRegistry the command registry for top-level command names
     * @param skillRegistrySupplier lazy supplier for the skill registry (may not be available at
     *     construction time); returns {@code null} if no registry is configured
     */
    public ReplCompleter(
            CommandRegistry commandRegistry,
            Supplier<SkillRegistry> skillRegistrySupplier) {
        this.commandRegistry = commandRegistry;
        this.skillRegistrySupplier = skillRegistrySupplier;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line();
        int cursor = line.cursor();

        // Only complete from the start of a line that looks like a command
        String upToCursor = buffer.substring(0, Math.min(cursor, buffer.length())).stripLeading();

        // If empty or just the prefix character, complete all commands
        if (upToCursor.isEmpty() || upToCursor.equals(":") || upToCursor.equals("/")) {
            completeTopLevel(candidates);
            return;
        }

        // Must start with : or / to be a command
        if (!upToCursor.startsWith(":") && !upToCursor.startsWith("/")) {
            return;
        }

        String withoutPrefix = upToCursor.substring(1);
        String[] tokens = withoutPrefix.split("\\s+", -1);

        if (tokens.length == 1) {
            // Completing the command name itself (e.g., ":sk" -> ":skill")
            String partial = tokens[0].toLowerCase();
            for (String name : commandRegistry.allCommandNames()) {
                if (name.startsWith(partial)) {
                    candidates.add(new Candidate(":" + name, name, null, null, null, null, true));
                }
            }
            return;
        }

        // Dispatch to command-specific completers
        String commandName = tokens[0].toLowerCase();
        if ("skill".equals(commandName)) {
            completeSkill(tokens, candidates);
        } else if ("memory".equals(commandName)) {
            completeMemory(tokens, candidates);
        }
    }

    private void completeTopLevel(List<Candidate> candidates) {
        for (String name : commandRegistry.allCommandNames()) {
            candidates.add(new Candidate(":" + name, name, null, null, null, null, true));
        }
    }

    private void completeSkill(String[] tokens, List<Candidate> candidates) {
        // tokens[0] = "skill", tokens[1] = subcommand or partial, tokens[2..] = args
        if (tokens.length == 2) {
            // Completing subcommand (e.g., ":skill lo" -> "load", "loaded")
            String partial = tokens[1].toLowerCase();
            for (String sub : SKILL_SUBCOMMANDS) {
                if (sub.startsWith(partial)) {
                    candidates.add(new Candidate(sub));
                }
            }
            return;
        }

        if (tokens.length == 3) {
            String sub = tokens[1].toLowerCase();
            String partial = tokens[2].toLowerCase();

            switch (sub) {
                case "load", "unload", "info" -> completeSkillNames(partial, candidates);
                case "list" -> completeCategories(partial, candidates);
                default -> { /* no completion for other subcommands */ }
            }
        }
    }

    private void completeSkillNames(String partial, List<Candidate> candidates) {
        SkillRegistry registry = skillRegistrySupplier.get();
        if (registry == null) {
            return;
        }
        for (SkillDefinition skill : registry.list()) {
            String name = skill.name();
            if (name.toLowerCase().startsWith(partial)) {
                candidates.add(new Candidate(name));
            }
        }
    }

    private void completeCategories(String partial, List<Candidate> candidates) {
        for (String cat : SKILL_CATEGORIES) {
            if (cat.startsWith(partial)) {
                candidates.add(new Candidate(cat));
            }
        }
    }

    private void completeMemory(String[] tokens, List<Candidate> candidates) {
        if (tokens.length == 2) {
            String partial = tokens[1].toLowerCase();
            for (String sub : MEMORY_SUBCOMMANDS) {
                if (sub.startsWith(partial)) {
                    candidates.add(new Candidate(sub));
                }
            }
            return;
        }

        if (tokens.length == 3) {
            String sub = tokens[1].toLowerCase();
            String partial = tokens[2].toLowerCase();
            if ("list".equals(sub) || "clear".equals(sub)) {
                for (String scope : MEMORY_SCOPES) {
                    if (scope.startsWith(partial)) {
                        candidates.add(new Candidate(scope));
                    }
                }
            }
        }
    }
}
