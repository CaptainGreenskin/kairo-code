package io.kairo.code.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void discoverFindsPluginsInDirectory() throws IOException {
        Path pluginsDir = tempDir.resolve("plugins/my-plugin");
        Files.createDirectories(pluginsDir);
        Files.writeString(pluginsDir.resolve("plugin.yaml"),
                "name: my-plugin\nversion: 1.0.0\ndescription: Test plugin\nenabled: true\n");

        var registry = new PluginRegistry(tempDir, null);
        registry.discover();

        assertThat(registry.list()).hasSize(1);
        assertThat(registry.list().get(0).name()).isEqualTo("my-plugin");
    }

    @Test
    void discoverMergesProjectAndUserPlugins() throws IOException {
        Path userDir = tempDir.resolve("user");
        Path projectDir = tempDir.resolve("project");

        createPlugin(userDir, "shared", "User version");
        createPlugin(userDir, "user-only", "User plugin");
        createPlugin(projectDir, "shared", "Project version");
        createPlugin(projectDir, "project-only", "Project plugin");

        var registry = new PluginRegistry(projectDir, userDir);
        registry.discover();

        assertThat(registry.list()).hasSize(3);
        // Project plugin overrides user plugin with same name
        var shared = registry.get("shared");
        assertThat(shared).isPresent();
        assertThat(shared.get().manifest().description()).isEqualTo("Project version");
    }

    @Test
    void discoverHandlesMissingDirectory() {
        var registry = new PluginRegistry(tempDir.resolve("nonexistent"), null);
        registry.discover();

        assertThat(registry.list()).isEmpty();
    }

    @Test
    void discoverSkipsDirsWithoutManifest() throws IOException {
        Path pluginsDir = tempDir.resolve("plugins/no-manifest");
        Files.createDirectories(pluginsDir);
        Files.writeString(pluginsDir.resolve("readme.md"), "Not a plugin");

        var registry = new PluginRegistry(tempDir, null);
        registry.discover();

        assertThat(registry.list()).isEmpty();
    }

    @Test
    void enableAndDisable() throws IOException {
        createPlugin(tempDir, "toggle-me", "Toggleable");

        var registry = new PluginRegistry(tempDir, null);
        registry.discover();
        assertThat(registry.get("toggle-me").get().enabled()).isFalse();

        assertThat(registry.enable("toggle-me")).isTrue();
        assertThat(registry.get("toggle-me").get().enabled()).isTrue();

        assertThat(registry.disable("toggle-me")).isTrue();
        assertThat(registry.get("toggle-me").get().enabled()).isFalse();
    }

    @Test
    void enableReturnsFalseForUnknownPlugin() throws IOException {
        var registry = new PluginRegistry(tempDir, null);
        registry.discover();

        assertThat(registry.enable("nonexistent")).isFalse();
    }

    @Test
    void enabledSkillDirsReturnsOnlyEnabledPlugins() throws IOException {
        Path pluginDir = tempDir.resolve("plugins/with-skills");
        Path skillsDir = pluginDir.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(pluginDir.resolve("plugin.yaml"),
                "name: with-skills\nenabled: true\n");
        Files.writeString(skillsDir.resolve("my-skill.md"),
                "---\nname: my-skill\n---\nSkill content");

        Path disabledPlugin = tempDir.resolve("plugins/disabled-plugin");
        Path disabledSkills = disabledPlugin.resolve("skills");
        Files.createDirectories(disabledSkills);
        Files.writeString(disabledPlugin.resolve("plugin.yaml"),
                "name: disabled-plugin\nenabled: false\n");

        var registry = new PluginRegistry(tempDir, null);
        registry.discover();

        var skillDirs = registry.enabledSkillDirs();
        assertThat(skillDirs).hasSize(1);
        assertThat(skillDirs.get(0)).isEqualTo(skillsDir);
    }

    @Test
    void enabledSkillDirsSkipsPluginsWithoutSkillsDir() throws IOException {
        Path pluginDir = tempDir.resolve("plugins/no-skills");
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("plugin.yaml"),
                "name: no-skills\nenabled: true\n");

        var registry = new PluginRegistry(tempDir, null);
        registry.discover();

        assertThat(registry.enabledSkillDirs()).isEmpty();
    }

    @Test
    void getReturnsEmptyForUnknown() throws IOException {
        var registry = new PluginRegistry(tempDir, null);
        registry.discover();

        assertThat(registry.get("nope")).isEmpty();
    }

    private void createPlugin(Path kairoDir, String name, String description) throws IOException {
        Path pluginDir = kairoDir.resolve("plugins/" + name);
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("plugin.yaml"),
                "name: " + name + "\ndescription: " + description + "\n");
    }
}
