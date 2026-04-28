package io.kairo.code.cli.commands;

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
 *   <li>{@code :skill loaded} — list currently active skills</li>
 *   <li>{@code :skill load <name>} — activate a skill (rebuilds session, preserves history)</li>
 *   <li>{@code :skill unload <name>} — deactivate a skill</li>
 * </ul>
 */
public class SkillCommand implements SlashCommand {

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description() {
        return "Manage skill loading (list / load / unload)";
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
            case "list" -> handleList(registry, context, writer);
            case "loaded" -> handleLoaded(context, writer);
            case "load" -> handleLoad(rest, registry, context, writer);
            case "unload" -> handleUnload(rest, context, writer);
            default -> printUsage(writer);
        }
    }

    private void handleList(SkillRegistry registry, ReplContext context, PrintWriter writer) {
        List<SkillDefinition> all = registry.list();
        if (all.isEmpty()) {
            writer.println("No skills registered.");
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
        writer.println("Usage: :skill <list|loaded|load <name>|unload <name>>");
        writer.flush();
    }
}
