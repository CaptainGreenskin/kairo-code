package io.kairo.code.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginManifestTest {

    @TempDir
    Path tempDir;

    @Test
    void parseMinimalYaml() throws IOException {
        String yaml = """
                name: my-plugin
                """;
        PluginManifest m = PluginManifest.fromYamlString(yaml);

        assertThat(m.name()).isEqualTo("my-plugin");
        assertThat(m.version()).isEqualTo("0.1.0");
        assertThat(m.description()).isEmpty();
        assertThat(m.skills()).isEmpty();
        assertThat(m.mcpServers()).isEmpty();
        assertThat(m.enabled()).isFalse();
    }

    @Test
    void parseFullYaml() throws IOException {
        String yaml = """
                name: sre-toolkit
                version: 2.1.0
                description: SRE diagnostic tools
                enabled: true
                skills:
                  - cpu-diagnosis.md
                  - memory-check.md
                mcp_servers:
                  - name: prometheus
                    command: npx
                    args:
                      - "-y"
                      - "prometheus-mcp"
                """;
        PluginManifest m = PluginManifest.fromYamlString(yaml);

        assertThat(m.name()).isEqualTo("sre-toolkit");
        assertThat(m.version()).isEqualTo("2.1.0");
        assertThat(m.description()).isEqualTo("SRE diagnostic tools");
        assertThat(m.enabled()).isTrue();
        assertThat(m.skills()).containsExactly("cpu-diagnosis.md", "memory-check.md");
        assertThat(m.mcpServers()).hasSize(1);
        assertThat(m.mcpServers().get(0).name()).isEqualTo("prometheus");
        assertThat(m.mcpServers().get(0).command()).isEqualTo("npx");
        assertThat(m.mcpServers().get(0).args()).containsExactly("-y", "prometheus-mcp");
    }

    @Test
    void parseFromFile() throws IOException {
        Path yaml = tempDir.resolve("plugin.yaml");
        Files.writeString(yaml, "name: file-plugin\nversion: 1.0.0\nenabled: true\n");

        PluginManifest m = PluginManifest.fromYaml(yaml);

        assertThat(m.name()).isEqualTo("file-plugin");
        assertThat(m.enabled()).isTrue();
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> PluginManifest.fromYamlString("name: \"\""))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withEnabledToggle() throws IOException {
        PluginManifest m = PluginManifest.fromYamlString("name: test\nenabled: false\n");
        assertThat(m.enabled()).isFalse();

        PluginManifest enabled = m.withEnabled(true);
        assertThat(enabled.enabled()).isTrue();
        assertThat(enabled.name()).isEqualTo("test");
    }

    @Test
    void ignoresUnknownFields() throws IOException {
        String yaml = """
                name: flexible
                future_field: some_value
                another_unknown: 42
                """;
        PluginManifest m = PluginManifest.fromYamlString(yaml);
        assertThat(m.name()).isEqualTo("flexible");
    }
}
