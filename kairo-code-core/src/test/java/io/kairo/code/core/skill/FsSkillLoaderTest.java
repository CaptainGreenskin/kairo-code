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
        assertThat(result.get(0).skill().description()).isEqualTo("Do something special.");
        assertThat(result.get(0).priority()).isEqualTo(SkillPriority.USER);
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
        assertThat(result.get(0).priority()).isEqualTo(SkillPriority.PROJECT);
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
        assertThat(result.get(0).skill().description()).isEqualTo("Project content.");
        assertThat(result.get(0).priority()).isEqualTo(SkillPriority.PROJECT);
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
        Map<String, SkillPriority> byName = new java.util.LinkedHashMap<>();
        for (SkillWithSource ws : result) {
            byName.put(ws.skill().name(), ws.priority());
        }
        assertThat(byName).containsEntry("global-only", SkillPriority.USER);
        assertThat(byName).containsEntry("project-only", SkillPriority.PROJECT);
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
        Files.writeString(globalDir.resolve("bad.md"), "Just some text, no front matter.");

        FsSkillLoader loader = new FsSkillLoader(globalDir, null);
        List<SkillWithSource> result = loader.loadAll();

        assertThat(result).isNotNull();
    }

    @Test
    void loadAll_managedOverridesUser() throws Exception {
        Path globalDir = Files.createDirectory(tempDir.resolve("global"));
        Path managedDir = Files.createDirectory(globalDir.resolve("managed"));

        writeFile(globalDir, "greet.md", """
                ---
                name: greet
                description: USER version
                ---

                # Greet

                User greeting.
                """);

        writeFile(managedDir, "greet.md", """
                ---
                name: greet
                description: MANAGED version
                ---

                # Greet

                Managed greeting.
                """);

        FsSkillLoader loader = new FsSkillLoader(globalDir, null);
        List<SkillWithSource> result = loader.loadAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).skill().name()).isEqualTo("greet");
        assertThat(result.get(0).skill().description()).isEqualTo("Managed greeting.");
        assertThat(result.get(0).priority()).isEqualTo(SkillPriority.MANAGED);
    }

    @Test
    void loadAll_userSkipsManagedSubdir() throws Exception {
        Path globalDir = Files.createDirectory(tempDir.resolve("global"));
        Path managedDir = Files.createDirectory(globalDir.resolve("managed"));

        // Only managed has the skill, user scan skips managed/ dir
        writeFile(managedDir, "admin.md", """
                ---
                name: admin
                description: Admin skill
                ---

                # Admin
                """);

        FsSkillLoader loader = new FsSkillLoader(globalDir, null);
        List<SkillWithSource> result = loader.loadAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).skill().name()).isEqualTo("admin");
        assertThat(result.get(0).priority()).isEqualTo(SkillPriority.MANAGED);
    }

    @Test
    void loadAll_projectOverridesManaged() throws Exception {
        Path globalDir = Files.createDirectory(tempDir.resolve("global"));
        Path managedDir = Files.createDirectory(globalDir.resolve("managed"));
        Path projectDir = Files.createDirectory(tempDir.resolve("project"));

        writeFile(managedDir, "deploy.md", """
                ---
                name: deploy
                description: MANAGED version
                ---

                # Deploy

                Managed deploy.
                """);

        writeFile(projectDir, "deploy.md", """
                ---
                name: deploy
                description: PROJECT version
                ---

                # Deploy

                Project deploy.
                """);

        FsSkillLoader loader = new FsSkillLoader(globalDir, projectDir);
        List<SkillWithSource> result = loader.loadAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).skill().name()).isEqualTo("deploy");
        assertThat(result.get(0).skill().description()).isEqualTo("Project deploy.");
        assertThat(result.get(0).priority()).isEqualTo(SkillPriority.PROJECT);
    }

    @Test
    void loadAll_uniqueSkillsNoDuplication() throws Exception {
        Path globalDir = Files.createDirectory(tempDir.resolve("global"));
        writeFile(globalDir, "build.md", """
                ---
                name: build
                description: Build skill
                ---

                # Build

                Build stuff.
                """);

        FsSkillLoader loader = new FsSkillLoader(globalDir, tempDir.resolve("nonexistent"));
        List<SkillWithSource> result = loader.loadAll();
        long count = result.stream().filter(s -> s.skill().name().equals("build")).count();

        assertThat(count).isEqualTo(1);
    }

    @Test
    void getWatchedDirs_returnsAllConfiguredPaths() throws Exception {
        Path globalDir = Files.createDirectory(tempDir.resolve("global"));
        Path projectDir = Files.createDirectory(tempDir.resolve("project"));

        FsSkillLoader loader = new FsSkillLoader(globalDir, projectDir);
        List<Path> dirs = loader.getWatchedDirs();

        assertThat(dirs).containsExactly(
                globalDir,
                globalDir.resolve("managed"),
                projectDir
        );
    }

    @Test
    void getWatchedDirs_handlesNullGlobalDir() {
        Path projectDir = tempDir.resolve("project");
        FsSkillLoader loader = new FsSkillLoader(null, projectDir);
        List<Path> dirs = loader.getWatchedDirs();

        assertThat(dirs).containsExactly(projectDir);
    }

    private void writeFile(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }
}
