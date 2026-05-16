package io.kairo.code.core.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Plugin manifest parsed from {@code plugin.yaml} inside a plugin directory.
 *
 * <p>Expected directory layout:
 * <pre>
 * .kairo-code/plugins/{name}/
 *   plugin.yaml          ← this manifest
 *   skills/              ← optional skill .md files
 *   tools/               ← reserved for future tool definitions
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PluginManifest(
        @JsonProperty("name") String name,
        @JsonProperty("version") String version,
        @JsonProperty("description") String description,
        @JsonProperty("skills") List<String> skills,
        @JsonProperty("mcp_servers") List<McpServerRef> mcpServers,
        @JsonProperty("enabled") boolean enabled) {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    public PluginManifest {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Plugin name is required");
        }
        if (version == null || version.isBlank()) {
            version = "0.1.0";
        }
        if (description == null) {
            description = "";
        }
        if (skills == null) {
            skills = List.of();
        }
        if (mcpServers == null) {
            mcpServers = List.of();
        }
    }

    /**
     * Parse a plugin.yaml file into a PluginManifest.
     */
    public static PluginManifest fromYaml(Path yamlFile) throws IOException {
        String content = Files.readString(yamlFile);
        return fromYamlString(content);
    }

    /**
     * Parse YAML content string into a PluginManifest.
     */
    public static PluginManifest fromYamlString(String yaml) throws IOException {
        return YAML_MAPPER.readValue(yaml, PluginManifest.class);
    }

    /**
     * Return a copy with the enabled flag toggled.
     */
    public PluginManifest withEnabled(boolean enabled) {
        return new PluginManifest(name, version, description, skills, mcpServers, enabled);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record McpServerRef(
            @JsonProperty("name") String name,
            @JsonProperty("command") String command,
            @JsonProperty("args") List<String> args) {

        public McpServerRef {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("MCP server name is required");
            }
            if (args == null) {
                args = List.of();
            }
        }
    }
}
