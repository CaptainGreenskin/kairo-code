package io.kairo.code.core.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.mcp.McpServerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code ~/.kairo-code/mcp.json} and holds a map of named MCP server entries.
 *
 * <p>JSON format (compatible with Claude Code's {@code mcpServers} field):
 *
 * <pre>{@code
 * {
 *   "mcpServers": {
 *     "filesystem": {
 *       "command": "npx",
 *       "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
 *     },
 *     "github": {
 *       "command": "npx",
 *       "args": ["-y", "@modelcontextprotocol/server-github"],
 *       "env": { "GITHUB_PERSONAL_ACCESS_TOKEN": "${GITHUB_TOKEN}" }
 *     }
 *   }
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpConfig(Map<String, McpServerEntry> servers) {

    private static final String DEFAULT_PATH = ".kairo-code/mcp.json";

    public McpConfig {
        if (servers == null) {
            servers = Collections.emptyMap();
        }
    }

    /** Static factory: read {@code ~/.kairo-code/mcp.json}; file missing → empty config. */
    public static McpConfig loadDefault() {
        Path path = Path.of(System.getProperty("user.home"), ".kairo-code", "mcp.json");
        return load(path);
    }

    /** Load from an explicit path; file missing → empty config. */
    public static McpConfig load(Path path) {
        if (path == null || !Files.exists(path)) {
            return new McpConfig(Collections.emptyMap());
        }
        try {
            String json = Files.readString(path);
            return parse(json);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load MCP config from " + path, e);
        }
    }

    /** Parse a JSON string into an {@link McpConfig}. */
    public static McpConfig parse(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            JsonNode serversNode = root.get("mcpServers");
            if (serversNode == null || !serversNode.isObject()) {
                return new McpConfig(Collections.emptyMap());
            }

            Map<String, McpServerEntry> servers = new LinkedHashMap<>();
            var fieldNames = serversNode.fieldNames();
            while (fieldNames.hasNext()) {
                String name = fieldNames.next();
                JsonNode entry = serversNode.get(name);
                servers.put(name, parseEntry(name, entry));
            }
            return new McpConfig(Collections.unmodifiableMap(servers));
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse MCP config JSON", e);
        }
    }

    private static McpServerEntry parseEntry(String name, JsonNode node) {
        String command = nonNullText(node, "command");
        List<String> args = parseStringArray(node, "args");
        Map<String, String> env = parseStringMap(node, "env");
        return new McpServerEntry(name, command, args, env);
    }

    private static String nonNullText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : "";
    }

    private static List<String> parseStringArray(JsonNode node, String field) {
        JsonNode arr = node.get(field);
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> result = new java.util.ArrayList<>();
        for (JsonNode element : arr) {
            if (!element.isNull()) {
                result.add(element.asText());
            }
        }
        return Collections.unmodifiableList(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseStringMap(JsonNode node, String field) {
        JsonNode mapNode = node.get(field);
        if (mapNode == null || !mapNode.isObject()) {
            return Map.of();
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.convertValue(mapNode, Map.class);
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    /**
     * A single MCP server entry from the JSON config.
     *
     * @param name server name (map key)
     * @param command the executable (e.g. "npx")
     * @param args arguments to the command
     * @param env environment variables with {@code ${VAR}} substitution support
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record McpServerEntry(
            String name,
            String command,
            List<String> args,
            Map<String, String> env
    ) {
        public McpServerEntry {
            if (args == null) args = List.of();
            if (env == null) env = Map.of();
        }

        /**
         * Convert to a {@link McpServerConfig} suitable for {@code McpClientRegistry.register()}.
         * Environment variables with {@code ${VAR}} patterns are resolved from the process env.
         */
        public McpServerConfig toServerConfig() {
            List<String> fullCommand = buildCommand();
            Map<String, String> resolvedEnv = resolveEnv();

            return McpServerConfig.builder()
                    .name(name)
                    .transportType(McpServerConfig.TransportType.STDIO)
                    .command(fullCommand)
                    .env(resolvedEnv)
                    .build();
        }

        private List<String> buildCommand() {
            List<String> result = new java.util.ArrayList<>();
            if (command != null && !command.isBlank()) {
                result.add(command);
            }
            result.addAll(args);
            return result;
        }

        private Map<String, String> resolveEnv() {
            Map<String, String> resolved = new LinkedHashMap<>();
            for (var entry : env.entrySet()) {
                resolved.put(entry.getKey(), resolveValue(entry.getValue()));
            }
            return resolved;
        }

        /**
         * Resolve a single env value — replaces {@code ${VAR}} with the process env.
         * Unset variables become empty strings.
         */
        private static String resolveValue(String value) {
            if (value == null) return "";
            StringBuilder result = new StringBuilder();
            int i = 0;
            while (i < value.length()) {
                int start = value.indexOf("${", i);
                if (start == -1) {
                    result.append(value.substring(i));
                    break;
                }
                result.append(value, i, start);
                int end = value.indexOf('}', start + 2);
                if (end == -1) {
                    result.append(value.substring(start));
                    break;
                }
                String varName = value.substring(start + 2, end);
                result.append(System.getenv(varName));
                i = end + 1;
            }
            return result.toString();
        }
    }
}
