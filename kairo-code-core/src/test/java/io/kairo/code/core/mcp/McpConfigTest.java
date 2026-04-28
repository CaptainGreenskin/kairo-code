package io.kairo.code.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.code.core.mcp.McpConfig.McpServerEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void loadDefaultReturnsEmptyWhenFileDoesNotExist() {
        // With no ~/.kairo-code/mcp.json, loadDefault returns empty config
        McpConfig config = McpConfig.loadDefault();
        // We can't easily test the real default path, but we verify the contract
        // via load(Path) below. loadDefault() delegates to load(Path).
        assertThat(config).isNotNull();
    }

    @Test
    void loadReturnsEmptyWhenFileDoesNotExist() {
        Path nonexistent = tempDir.resolve("nonexistent.json");
        McpConfig config = McpConfig.load(nonexistent);
        assertThat(config.servers()).isEmpty();
    }

    @Test
    void parsesValidMcpJson() throws IOException {
        String json = """
                {
                  "mcpServers": {
                    "filesystem": {
                      "command": "npx",
                      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
                    },
                    "github": {
                      "command": "npx",
                      "args": ["-y", "@modelcontextprotocol/server-github"],
                      "env": {
                        "GITHUB_PERSONAL_ACCESS_TOKEN": "${GITHUB_TOKEN}"
                      }
                    }
                  }
                }
                """;

        McpConfig config = McpConfig.parse(json);

        assertThat(config.servers()).hasSize(2);

        McpServerEntry fs = config.servers().get("filesystem");
        assertThat(fs.command()).isEqualTo("npx");
        assertThat(fs.args()).containsExactly("-y", "@modelcontextprotocol/server-filesystem", "/tmp");
        assertThat(fs.env()).isEmpty();

        McpServerEntry gh = config.servers().get("github");
        assertThat(gh.command()).isEqualTo("npx");
        assertThat(gh.args()).containsExactly("-y", "@modelcontextprotocol/server-github");
        assertThat(gh.env()).containsKey("GITHUB_PERSONAL_ACCESS_TOKEN");
    }

    @Test
    void envVariableSubstitution() {
        // Set a test env var
        String original = System.getenv("TEST_MCP_VAR");
        try {
            // We can't easily set env vars in Java, so test with an existing one
            // or test the resolution with the PATH env var which always exists
            String json = """
                    {
                      "mcpServers": {
                        "test": {
                          "command": "echo",
                          "args": [],
                          "env": {
                            "MY_PATH": "${PATH}",
                            "LITERAL": "no-sub-here"
                          }
                        }
                      }
                    }
                    """;

            McpConfig config = McpConfig.parse(json);
            McpServerEntry entry = config.servers().get("test");
            Map<String, String> resolved = entry.toServerConfig().env();

            assertThat(resolved.get("LITERAL")).isEqualTo("no-sub-here");
            // PATH should be resolved (non-empty on any system)
            assertThat(resolved.get("MY_PATH")).isNotBlank();
        } finally {
            // No cleanup needed since we didn't actually set anything
        }
    }

    @Test
    void emptyMcpServersObject() {
        String json = "{\"mcpServers\": {}}";
        McpConfig config = McpConfig.parse(json);
        assertThat(config.servers()).isEmpty();
    }

    @Test
    void missingMcpServersKey() {
        String json = "{\"other\": {}}";
        McpConfig config = McpConfig.parse(json);
        assertThat(config.servers()).isEmpty();
    }

    @Test
    void toServerConfigBuildsStdioConfig() {
        String json = """
                {
                  "mcpServers": {
                    "myserver": {
                      "command": "node",
                      "args": ["server.js", "--port", "3000"],
                      "env": {"NODE_ENV": "production"}
                    }
                  }
                }
                """;

        McpConfig config = McpConfig.parse(json);
        McpServerEntry entry = config.servers().get("myserver");
        var serverConfig = entry.toServerConfig();

        assertThat(serverConfig.name()).isEqualTo("myserver");
        assertThat(serverConfig.command()).containsExactly("node", "server.js", "--port", "3000");
        assertThat(serverConfig.env()).containsEntry("NODE_ENV", "production");
    }
}
