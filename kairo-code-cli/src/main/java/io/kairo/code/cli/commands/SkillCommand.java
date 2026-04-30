package io.kairo.code.cli.commands;

import io.kairo.api.skill.SkillCategory;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import java.io.PrintWriter;
import java.util.List;
import java.util.Set;

/**
 * Manages skill loading for the current session.
 *
 * <p>Skills are reusable instruction blocks (Markdown with YAML front-matter) that get injected
 * into the system prompt under the {@code ## Active Skills} section. They turn one-off prompt
 * engineering ("review this code carefully") into reusable, named capabilities.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code :skill list} — list all registered skills</li>
 *   <li>{@code :skill list <category>} — list skills filtered by category</li>
 *   <li>{@code :skill loaded} — list currently active skills</li>
 *   <li>{@code :skill load <name>} — activate a skill (rebuilds session, preserves history)</li>
 *   <li>{@code :skill unload <name>} — deactivate a skill</li>
 *   <li>{@code :skill info <name>} — show detailed skill metadata</li>
 * </ul>
 */
public class SkillCommand implements SlashCommand {

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description() {
        return "Manage skill loading (list / load / unload / info)";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        SkillRegistry registry = context.skillRegistry();
        if (registry == null) {
            writer.println("Skills unavailable: no skill registry configured.");
            writer.flush();
            return;
        }

        String trimmed = args == null ? "" : args.strip();
        if (trimmed.isEmpty()) {
            printUsage(writer);
            return;
        }

        String[] parts = trimmed.split("\\s+", 2);
        String sub = parts[0].toLowerCase();
        String rest = parts.length > 1 ? parts[1].strip() : "";

        switch (sub) {
            case "list" -> handleList(rest, registry, context, writer);
            case "loaded" -> handleLoaded(context, writer);
            case "load" -> handleLoad(rest, registry, context, writer);
            case "unload" -> handleUnload(rest, context, writer);
            case "info" -> handleInfo(rest, registry, context, writer);
            default -> printUsage(writer);
        }
    }

    private void handleList(String categoryFilter, SkillRegistry registry, ReplContext context,
            PrintWriter writer) {
        List<SkillDefinition> all;
        if (!categoryFilter.isBlank()) {
            SkillCategory category;
            try {
                category = SkillCategory.valueOf(categoryFilter.toUpperCase());
            } catch (IllegalArgumentException e) {
                writer.println("Unknown category: " + categoryFilter
                        + ". Valid categories: CODE, DEVOPS, DATA, TESTING, DOCUMENTATION, GENERAL");
                writer.flush();
                return;
            }
            all = registry.listByCategory(category);
        } else {
            all = registry.list();
        }
        if (all.isEmpty()) {
            writer.println(categoryFilter.isBlank()
                    ? "No skills registered."
                    : "No skills in category: " + categoryFilter.toUpperCase());
            writer.flush();
            return;
        }
        Set<String> loaded = context.loadedSkills();
        writer.println();
        writer.println("Available skills:");
        for (SkillDefinition skill : all) {
            String marker = loaded.contains(skill.name()) ? "●" : "○";
            String desc = skill.description() == null ? "" : skill.description();
            String source = context.skillSources().getOrDefault(skill.name(), "");
            String sourceTag = source.isBlank() ? "" : "  [" + source + "]";
            writer.printf("  %s %-20s%s  %s%n", marker, skill.name(), sourceTag, desc);
        }
        writer.println();
        writer.println("● = loaded   ○ = available");
        writer.flush();
    }

    private void handleLoaded(ReplContext context, PrintWriter writer) {
        Set<String> loaded = context.loadedSkills();
        if (loaded.isEmpty()) {
            writer.println("No skills currently loaded. Use :skill load <name> to activate one.");
        } else {
            writer.println("Loaded skills: " + String.join(", ", loaded));
        }
        writer.flush();
    }

    private void handleLoad(
            String name, SkillRegistry registry, ReplContext context, PrintWriter writer) {
        if (name.isBlank()) {
            writer.println("Usage: :skill load <name>");
            writer.flush();
            return;
        }
        if (registry.get(name).isEmpty()) {
            writer.println("Unknown skill: " + name + ". Run :skill list to see available skills.");
            writer.flush();
            return;
        }
        if (context.loadedSkills().contains(name)) {
            writer.println("Skill already loaded: " + name);
            writer.flush();
            return;
        }
        context.loadedSkills().add(name);
        try {
            context.reloadSkills();
            writer.println("✓ Loaded skill: " + name);
        } catch (RuntimeException e) {
            // Roll back the loaded set if rebuild failed so state stays coherent.
            context.loadedSkills().remove(name);
            writer.println("Failed to load skill: " + e.getMessage());
        }
        writer.flush();
    }

    private void handleUnload(String name, ReplContext context, PrintWriter writer) {
        if (name.isBlank()) {
            writer.println("Usage: :skill unload <name>");
            writer.flush();
            return;
        }
        if (!context.loadedSkills().contains(name)) {
            writer.println("Skill not loaded: " + name);
            writer.flush();
            return;
        }
        context.loadedSkills().remove(name);
        try {
            context.reloadSkills();
            writer.println("✓ Unloaded skill: " + name);
        } catch (RuntimeException e) {
            context.loadedSkills().add(name);
            writer.println("Failed to unload skill: " + e.getMessage());
        }
        writer.flush();
    }

    private void printUsage(PrintWriter writer) {
        writer.println("Usage: :skill <list [category]|loaded|load <name>|unload <name>|info <name>>");
        writer.flush();
    }

    private void handleInfo(String name, SkillRegistry registry, ReplContext context,
            PrintWriter writer) {
        if (name.isBlank()) {
            writer.println("Usage: :skill info <name>");
            writer.flush();
            return;
        }
        var opt = registry.get(name);
        if (opt.isEmpty()) {
            writer.println("Unknown skill: " + name + ". Run :skill list to see available skills.");
            writer.flush();
            return;
        }
        SkillDefinition skill = opt.get();
        writer.println();
        writer.println("Skill: " + skill.name());
        if (skill.version() != null) {
            writer.println("  Version:     " + skill.version());
        }
        if (skill.category() != null) {
            writer.println("  Category:    " + skill.category());
        }
        if (skill.description() != null) {
            writer.println("  Description: " + skill.description());
        }
        String source = context.skillSources().getOrDefault(skill.name(), "");
        if (!source.isBlank()) {
            writer.println("  Source:      " + source);
        }
        if (skill.triggerConditions() != null && !skill.triggerConditions().isEmpty()) {
            writer.println("  Triggers:    " + String.join(", ", skill.triggerConditions()));
        }
        if (skill.pathPatterns() != null && !skill.pathPatterns().isEmpty()) {
            writer.println("  Paths:       " + String.join(", ", skill.pathPatterns()));
        }
        if (skill.requiredTools() != null && !skill.requiredTools().isEmpty()) {
            writer.println("  Req. Tools:  " + String.join(", ", skill.requiredTools()));
        }
        if (skill.platform() != null) {
            writer.println("  Platform:    " + skill.platform());
        }
        if (skill.matchScore() != 0) {
            writer.println("  Match Score: " + skill.matchScore());
        }
        boolean loaded = context.loadedSkills().contains(skill.name());
        writer.println("  Status:      " + (loaded ? "loaded" : "available"));
        writer.println();
        writer.flush();
    }
}
