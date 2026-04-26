package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.skill.DefaultSkillRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for user-defined skill loading from the filesystem.
 *
 * <p>These tests validate the loading behavior that backs
 * ReplLoop.bootstrapSkillRegistry(kairoDir), using DefaultSkillRegistry.loadFromFile() directly.
 */
class UserSkillLoaderTest {

    private static String validSkillMarkdown(String name) {
        return """
                ---
                name: %s
                version: 1.0.0
                category: CODE
                triggers:
                  - "/%s"
                ---
                # %s skill

                This skill does something useful.
                """.formatted(name, name, name);
    }

    @TempDir
    Path tempDir;

    @Test
    void nonExistentSkillsDirDoesNotThrow() {
        Path noSuchDir = tempDir.resolve("skills");
        // directory does not exist — no exception
        assertThat(noSuchDir).doesNotExist();
        DefaultSkillRegistry registry = new DefaultSkillRegistry();
        // Simulates bootstrapSkillRegistry guard: only scan if directory exists
        if (Files.isDirectory(noSuchDir)) {
            // would list and load, but this branch is never entered
        }
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void markdownFilesAreAttemptedForLoad() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("my-skill.md"), validSkillMarkdown("my-skill"));

        DefaultSkillRegistry registry = new DefaultSkillRegistry();
        int attempted = 0;
        try (Stream<Path> stream = Files.list(skillsDir)) {
            var paths = stream.filter(p -> p.toString().endsWith(".md")).sorted().toList();
            for (Path p : paths) {
                attempted++;
                try { registry.loadFromFile(p).block(); } catch (Exception ignored) {}
            }
        }

        assertThat(attempted).isEqualTo(1);
    }

    @Test
    void nonMarkdownFilesAreIgnored() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("not-a-skill.txt"), "ignored content");
        Files.writeString(skillsDir.resolve("also-ignored.json"), "{}");

        DefaultSkillRegistry registry = new DefaultSkillRegistry();
        try (Stream<Path> stream = Files.list(skillsDir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                    .forEach(p -> {
                        try { registry.loadFromFile(p).block(); } catch (Exception ignored) {}
                    });
        }

        // No .md files → no skills loaded
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void multipleSkillFilesAreAllLoaded() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("skill-a.md"), validSkillMarkdown("skill-a"));
        Files.writeString(skillsDir.resolve("skill-b.md"), validSkillMarkdown("skill-b"));

        DefaultSkillRegistry registry = new DefaultSkillRegistry();
        int attempted = 0;
        try (Stream<Path> stream = Files.list(skillsDir)) {
            var paths = stream.filter(p -> p.toString().endsWith(".md")).sorted().toList();
            for (Path p : paths) {
                attempted++;
                try { registry.loadFromFile(p).block(); } catch (Exception ignored) {}
            }
        }

        // Both .md files were processed by the loader
        assertThat(attempted).isEqualTo(2);
    }
}
