package io.kairo.code.cli.demo;

import io.kairo.api.skill.SkillCategory;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.code.cli.ReplContext;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Demonstrates the self-evolution flow:
 * <ol>
 *   <li>List current skills via {@code SkillRegistry.list()}</li>
 *   <li>Detect a missing "format-json" skill</li>
 *   <li>Generate skill Markdown and write it to the user skills directory</li>
 *   <li>Register the new skill</li>
 *   <li>List skills again to confirm it appears</li>
 * </ol>
 */
public class SelfEvolutionDemo {

    private static final String SKILL_NAME = "format-json";
    private static final String SKILL_MARKDOWN = """
            ---
            name: format-json
            description: Format and pretty-print JSON content
            version: 1.0.0
            category: GENERAL
            ---

            # format-json

            Pretty-print JSON input for readability.

            ## Usage

            Provide raw JSON and this skill will return formatted output with proper indentation.
            """;

    private SelfEvolutionDemo() {
    }

    public static void run(ReplContext context) {
        PrintWriter writer = context.writer();
        SkillRegistry registry = context.skillRegistry();
        if (registry == null) {
            writer.println("Self-evolution unavailable: no skill registry configured.");
            writer.flush();
            return;
        }

        writer.println("=== Self-Evolution Demo ===");
        writer.println();

        // Step 1: List current skills
        writer.println("Step 1: Listing current skills...");
        List<SkillDefinition> skills = registry.list();
        writer.println("Available skills:");
        Set<String> existingNames = skills.stream().map(SkillDefinition::name).collect(java.util.stream.Collectors.toSet());
        for (SkillDefinition s : skills) {
            String marker = context.loadedSkills().contains(s.name()) ? "●" : "○";
            writer.printf("  %s %s  %s%n", marker, s.name(), s.description() != null ? s.description() : "");
        }
        writer.println();

        // Step 2: Detect skill gap
        writer.println("Step 2: Checking for skill gaps...");
        if (existingNames.contains(SKILL_NAME)) {
            writer.println("Skill '" + SKILL_NAME + "' already exists. No gap detected.");
            writer.flush();
            return;
        }
        writer.println("Skill gap detected: " + SKILL_NAME);
        writer.println();

        // Step 3: Write skill Markdown to user skills directory
        writer.println("Step 3: Creating skill '" + SKILL_NAME + "'...");
        Path userSkillsDir = Path.of(System.getProperty("user.home"), ".kairo-code", "skills");
        try {
            Files.createDirectories(userSkillsDir);
            Path skillPath = userSkillsDir.resolve(SKILL_NAME + ".md");
            Files.writeString(skillPath, SKILL_MARKDOWN);
            writer.println("  Wrote: " + skillPath);
        } catch (IOException e) {
            writer.println("  Failed to write skill file: " + e.getMessage());
            writer.flush();
            return;
        }

        // Step 4: Register the skill
        SkillDefinition newSkill = new SkillDefinition(
                SKILL_NAME,
                "1.0.0",
                "Format and pretty-print JSON content",
                SKILL_MARKDOWN,
                List.of(),
                SkillCategory.GENERAL,
                null, null, null, 0, null);
        registry.register(newSkill);
        try {
            context.skillSources().put(SKILL_NAME, "user");
        } catch (UnsupportedOperationException e) {
            // skillSources may be an immutable map in test contexts — skip silently
        }
        writer.println("  Registered skill: " + SKILL_NAME);
        writer.println();

        // Step 5: Reload skills and confirm
        writer.println("Step 4: Reloading skills and verifying...");
        context.reloadSkills();
        context.loadedSkills().add(SKILL_NAME);

        List<SkillDefinition> updatedSkills = registry.list();
        writer.println("Available skills:");
        for (SkillDefinition s : updatedSkills) {
            String marker = context.loadedSkills().contains(s.name()) ? "●" : "○";
            writer.printf("  %s %s  %s%n", marker, s.name(), s.description() != null ? s.description() : "");
        }
        writer.println();

        boolean found = updatedSkills.stream().anyMatch(s -> s.name().equals(SKILL_NAME));
        if (found) {
            writer.println("Self-evolution complete. New skill '" + SKILL_NAME + "' is now active.");
        } else {
            writer.println("Warning: skill '" + SKILL_NAME + "' did not appear after registration.");
        }
        writer.flush();
    }
}
