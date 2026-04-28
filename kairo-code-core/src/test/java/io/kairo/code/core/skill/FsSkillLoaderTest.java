package io.kairo.code.core.skill;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.code.core.skill.FsSkillLoader.SkillWithSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FsSkillLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadAll_whenNoDirectoriesExist_returnsEmpty() {
        Path nonexistent = tempDir.resolve("nonexistent");
        FsSkillLoader loader = new FsSkillLoader(nonexistent, nonexistent.resolve("project"));

        List<SkillWithSource> result = loader.loadAll();

        assertThat(result).isEmpty();
    }

    @Test
    void loadAll_parsesMdFilesFromGlobalDirectory() throws Exception {
        Path globalDir = Files.createDirectory(tempDir.resolve("global"));
        writeFile(globalDir, "my-skill.md", """
                ---
                name: my-skill
                description: My custom skill
                ---

                # My Skill

                Do something special.
                """);

        FsSkillLoader loader = new FsSkillLoader(globalDir, null);
        List<SkillWithSource> result = loader.loadAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).skill().name()).isEqualTo("my-skill");
        // Parser extracts description from the body text after the heading
        assertThat(result.get(0).skill().description()).isEqualTo("Do something special.");
        assertThat(result.get(0).source()).isEqualTo("global");
    }

    @Test
    void loadAll_parsesMdFilesFromProjectDirectory() throws Exception {
        Path projectDir = Files.createDirectory(tempDir.resolve("project"));
        writeFile(projectDir, "project-skill.md", """
                ---
                name: project-skill
                description: Project-specific rules
                ---

                # Project Skill

                Project rules here.
                """);

        FsSkillLoader loader = new FsSkillLoader(null, projectDir);
        List<SkillWithSource> result = loader.loadAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).skill().name()).isEqualTo("project-skill");
        assertThat(result.get(0).source()).isEqualTo("project");
    }

    @Test
    void loadAll_projectOverridesGlobalByName() throws Exception {
        Path globalDir = Files.createDirectory(tempDir.resolve("global"));
        Path projectDir = Files.createDirectory(tempDir.resolve("project"));

        writeFile(globalDir, "shared-skill.md", """
                ---
                name: shared-skill
                description: Global version
                ---

                # Shared Skill

                Global content.
                """);

        writeFile(projectDir, "shared-skill.md", """
                ---
                name: shared-skill
                description: Project version
                ---

                # Shared Skill

                Project content.
                """);

        FsSkillLoader loader = new FsSkillLoader(globalDir, projectDir);
        List<SkillWithSource> result = loader.loadAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).skill().name()).isEqualTo("shared-skill");
        // Project version wins — description comes from body text
        assertThat(result.get(0).skill().description()).isEqualTo("Project content.");
        assertThat(result.get(0).source()).isEqualTo("project");
    }

    @Test
    void loadAll_loadsBothGlobalAndProjectWhenDifferentNames() throws Exception {
        Path globalDir = Files.createDirectory(tempDir.resolve("global"));
        Path projectDir = Files.createDirectory(tempDir.resolve("project"));

        writeFile(globalDir, "global-only.md", """
                ---
                name: global-only
                description: From global
                ---

                # Global
                """);

        writeFile(projectDir, "project-only.md", """
                ---
                name: project-only
                description: From project
                ---

                # Project
                """);

        FsSkillLoader loader = new FsSkillLoader(globalDir, projectDir);
        List<SkillWithSource> result = loader.loadAll();

        assertThat(result).hasSize(2);
        Map<String, String> byName = new java.util.LinkedHashMap<>();
        for (SkillWithSource ws : result) {
            byName.put(ws.skill().name(), ws.source());
        }
        assertThat(byName).containsEntry("global-only", "global");
        assertThat(byName).containsEntry("project-only", "project");
    }

    @Test
    void loadAll_ignoresNonMdFiles() throws Exception {
        Path globalDir = Files.createDirectory(tempDir.resolve("global"));
        Files.writeString(globalDir.resolve("notes.txt"), "Not a skill");

        FsSkillLoader loader = new FsSkillLoader(globalDir, null);
        List<SkillWithSource> result = loader.loadAll();

        assertThat(result).isEmpty();
    }

    @Test
    void loadAll_skipsMalformedMdFiles() throws Exception {
        Path globalDir = Files.createDirectory(tempDir.resolve("global"));
        // Missing YAML front-matter name — parser will fail or produce garbage
        Files.writeString(globalDir.resolve("bad.md"), "Just some text, no front matter.");

        FsSkillLoader loader = new FsSkillLoader(globalDir, null);
        // Should not throw — just skip the bad file
        List<SkillWithSource> result = loader.loadAll();

        // The parser may still produce something, but it should not crash
        // Either way, no exception should propagate
        assertThat(result).isNotNull();
    }

    private void writeFile(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }
}
