package io.kairo.code.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.controller.McpController;
import io.kairo.code.server.controller.McpController.McpServerEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link McpController} covering the .mcp.json read/write lifecycle.
 */
class McpControllerTest {

    @TempDir
    Path tempDir;

    private McpController controller;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        ServerProperties props = new ServerProperties(
                "openai", "gpt-4o", tempDir.toString(),
                "https://api.openai.com", "sk-test");
        controller = new McpController(props, objectMapper);
    }

    @Test
    void listServers_emptyWhenNoFile() throws IOException {
        assertThat(controller.listServers()).isEmpty();
    }

    @Test
    void addAndList_roundtrip() throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("API_KEY", "abc");
        var server = new McpServerEntry("my-mcp", "node",
                List.of("/srv.js", "--port", "8080"), env, false);
        controller.addServer(server);

        var list = controller.listServers();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).name()).isEqualTo("my-mcp");
        assertThat(list.get(0).command()).isEqualTo("node");
        assertThat(list.get(0).args()).containsExactly("/srv.js", "--port", "8080");
        assertThat(list.get(0).env()).containsEntry("API_KEY", "abc");
        assertThat(list.get(0).disabled()).isFalse();
    }

    @Test
    void addServer_writesFileToWorkingDir() throws IOException {
        var server = new McpServerEntry("written", "cmd", List.of(), null, false);
        controller.addServer(server);

        Path mcpFile = tempDir.resolve(".mcp.json");
        assertThat(Files.exists(mcpFile)).isTrue();
        String content = Files.readString(mcpFile, StandardCharsets.UTF_8);
        assertThat(content).contains("mcpServers").contains("written");
    }

    @Test
    void addDuplicate_returnsConflict() throws IOException {
        var server = new McpServerEntry("dup", "cmd", List.of(), null, false);
        controller.addServer(server);

        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.addServer(server));
        assertThat(ex.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void toggle_flipsDisabled() throws IOException {
        controller.addServer(new McpServerEntry("tog", "cmd", List.of(), null, false));

        var toggled = controller.toggleServer("tog");
        assertThat(toggled.disabled()).isTrue();

        var toggled2 = controller.toggleServer("tog");
        assertThat(toggled2.disabled()).isFalse();
    }

    @Test
    void toggle_unknownName_returnsNotFound() {
        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.toggleServer("missing"));
        assertThat(ex.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void update_replacesServerFields() throws IOException {
        controller.addServer(new McpServerEntry("upd", "old-cmd", List.of("a"), null, false));

        Map<String, String> env = new LinkedHashMap<>();
        env.put("X", "1");
        var updated = controller.updateServer("upd",
                new McpServerEntry("ignored-name", "new-cmd", List.of("b", "c"), env, true));

        assertThat(updated.name()).isEqualTo("upd");
        assertThat(updated.command()).isEqualTo("new-cmd");
        assertThat(updated.args()).containsExactly("b", "c");

        var fresh = controller.listServers();
        assertThat(fresh).hasSize(1);
        assertThat(fresh.get(0).command()).isEqualTo("new-cmd");
        assertThat(fresh.get(0).env()).containsEntry("X", "1");
        assertThat(fresh.get(0).disabled()).isTrue();
    }

    @Test
    void update_unknownName_returnsNotFound() {
        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateServer("missing",
                        new McpServerEntry("missing", "cmd", List.of(), null, false)));
        assertThat(ex.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void delete_removesServer() throws IOException {
        controller.addServer(new McpServerEntry("del-me", "cmd", List.of(), null, false));

        var resp = controller.deleteServer("del-me");
        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        assertThat(controller.listServers()).isEmpty();
    }

    @Test
    void delete_unknownName_returnsNotFound() throws IOException {
        var resp = controller.deleteServer("never-existed");
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void invalidName_throwsBadRequest() {
        var bad = new McpServerEntry("../evil", "cmd", List.of(), null, false);
        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.addServer(bad));
        assertThat(ex.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void emptyName_throwsBadRequest() {
        var bad = new McpServerEntry("", "cmd", List.of(), null, false);
        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.addServer(bad));
        assertThat(ex.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void readConfig_recoversFromMalformedFile() throws IOException {
        Files.writeString(tempDir.resolve(".mcp.json"), "{ this is not json",
                StandardCharsets.UTF_8);
        // Should not throw — should treat as empty and recover on next write.
        assertThat(controller.listServers()).isEmpty();

        controller.addServer(new McpServerEntry("recover", "cmd", List.of(), null, false));
        assertThat(controller.listServers()).hasSize(1);
    }

    @Test
    void writtenFile_isClaudeCodeCompatible() throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("TOKEN", "xyz");
        controller.addServer(new McpServerEntry("compat", "node",
                List.of("server.js"), env, false));

        String content = Files.readString(tempDir.resolve(".mcp.json"), StandardCharsets.UTF_8);
        var parsed = objectMapper.readTree(content);
        assertThat(parsed.has("mcpServers")).isTrue();
        var server = parsed.path("mcpServers").path("compat");
        assertThat(server.path("command").asText()).isEqualTo("node");
        assertThat(server.path("args").get(0).asText()).isEqualTo("server.js");
        assertThat(server.path("env").path("TOKEN").asText()).isEqualTo("xyz");
        assertThat(server.path("disabled").asBoolean()).isFalse();
    }
}
