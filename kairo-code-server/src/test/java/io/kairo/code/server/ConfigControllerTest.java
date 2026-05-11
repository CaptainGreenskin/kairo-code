package io.kairo.code.server;

import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.controller.ConfigController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ConfigController} file browsing endpoints.
 */
class ConfigControllerTest {

    @TempDir
    Path tempDir;

    private ConfigController controller;

    @BeforeEach
    void setUp() throws IOException {
        // Create some test files
        Files.writeString(tempDir.resolve("hello.txt"), "Hello, World!");
        Files.writeString(tempDir.resolve("test.java"), "public class Test {}");
        Path subDir = tempDir.resolve("subdir");
        Files.createDirectory(subDir);
        Files.writeString(subDir.resolve("nested.py"), "print('hello')");

        ServerProperties props = new ServerProperties(
                "openai", "gpt-4o", tempDir.toString(),
                "https://api.openai.com", "sk-test");
        controller = new ConfigController(props, null, null, null);
    }

    @Test
    void getConfig_returnsConfigWithoutApiKey() {
        var response = controller.getConfig();

        assertThat(response.model()).isEqualTo("gpt-4o");
        assertThat(response.provider()).isEqualTo("openai");
        assertThat(response.baseUrl()).isEqualTo("https://api.openai.com");
        // apiKey must NOT be exposed — only a boolean flag
        assertThat(response.apiKeySet()).isTrue();
    }

    @Test
    void getConfig_noApiKey_returnsApiKeySetFalse() {
        ServerProperties propsNoKey = new ServerProperties(
                "anthropic", "claude-sonnet-4-20250514", tempDir.toString(),
                "https://api.anthropic.com", "");
        ConfigController c = new ConfigController(propsNoKey, null, null, null);

        var response = c.getConfig();

        assertThat(response.provider()).isEqualTo("anthropic");
        assertThat(response.model()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(response.apiKeySet()).isFalse();
    }

    @Test
    void getConfig_noApiKey_apiKeySetIsFalse() {
        ServerProperties propsNoKey = new ServerProperties(
                "anthropic", "claude-3-5-sonnet-20241022", tempDir.toString(),
                "https://api.anthropic.com", "");
        ConfigController ctrlNoKey = new ConfigController(propsNoKey, null, null, null);

        var config = ctrlNoKey.getConfig();

        assertThat(config.provider()).isEqualTo("anthropic");
        assertThat(config.model()).isEqualTo("claude-3-5-sonnet-20241022");
        assertThat(config.baseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(config.apiKeySet()).isFalse();
    }

    @Test
    void getConfig_noApiKey_apiKeySetFalse() {
        ServerProperties props = new ServerProperties(
                "anthropic", "claude-3-5-sonnet-20241022", tempDir.toString(),
                "https://api.anthropic.com", null);
        ConfigController c = new ConfigController(props, null, null, null);

        var response = c.getConfig();

        assertThat(response.provider()).isEqualTo("anthropic");
        assertThat(response.model()).isEqualTo("claude-3-5-sonnet-20241022");
        assertThat(response.baseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(response.apiKeySet()).isFalse();
    }

    @Test
    void listFiles_returnsRootEntries() {
        var entries = controller.listFiles("", null);

        assertThat(entries).hasSize(3);
        assertThat(entries).extracting("name").containsExactlyInAnyOrder(
                "hello.txt", "subdir", "test.java");

        var txtFile = entries.stream().filter(e -> e.name().equals("hello.txt")).findFirst().orElseThrow();
        assertThat(txtFile.isDir()).isFalse();
        assertThat(txtFile.size()).isEqualTo(13);

        var dir = entries.stream().filter(e -> e.name().equals("subdir")).findFirst().orElseThrow();
        assertThat(dir.isDir()).isTrue();
    }

    @Test
    void listFiles_returnsSubDirectoryEntries() {
        var entries = controller.listFiles("subdir", null);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).name()).isEqualTo("nested.py");
        assertThat(entries.get(0).path()).isEqualTo("subdir/nested.py");
    }

    @Test
    void listFiles_preventsPathTraversal() {
        assertThatThrownBy(() -> controller.listFiles("../../etc", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    var ex = (ResponseStatusException) e;
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                });
    }

    @Test
    void listFiles_nonExistentDirectory_returns404() {
        assertThatThrownBy(() -> controller.listFiles("nonexistent", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    var ex = (ResponseStatusException) e;
                    assertThat(ex.getStatusCode().value()).isEqualTo(404);
                });
    }

    @Test
    void getFileContent_returnsContent() {
        var response = controller.getFileContent("hello.txt", null);

        assertThat(response.path()).isEqualTo("hello.txt");
        assertThat(response.content()).isEqualTo("Hello, World!");
        assertThat(response.language()).isEqualTo("");

        var javaResponse = controller.getFileContent("test.java", null);
        assertThat(javaResponse.content()).isEqualTo("public class Test {}");
        assertThat(javaResponse.language()).isEqualTo("java");

        var pyResponse = controller.getFileContent("subdir/nested.py", null);
        assertThat(pyResponse.content()).isEqualTo("print('hello')");
        assertThat(pyResponse.language()).isEqualTo("python");
    }

    @Test
    void getFileContent_rejectsTooLarge() throws IOException {
        // Create a file larger than 100KB
        Path largeFile = tempDir.resolve("large.bin");
        byte[] data = new byte[100_001];
        Files.write(largeFile, data);

        assertThatThrownBy(() -> controller.getFileContent("large.bin", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    var ex = (ResponseStatusException) e;
                    assertThat(ex.getStatusCode().value()).isEqualTo(413);
                });
    }

    @Test
    void getFileContent_preventsPathTraversal() {
        assertThatThrownBy(() -> controller.getFileContent("../../etc/passwd", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    var ex = (ResponseStatusException) e;
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                });
    }

    @Test
    void getFileContent_nonExistentFile_returns404() {
        assertThatThrownBy(() -> controller.getFileContent("nonexistent.txt", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    var ex = (ResponseStatusException) e;
                    assertThat(ex.getStatusCode().value()).isEqualTo(404);
                });
    }

    @Test
    void putFileContent_writesFileSuccessfully() throws IOException {
        String testPath = "test-memory.md";
        String testContent = "# Test Memory\n\nHello world";

        var result = controller.putFileContent(testPath, null, testContent);

        assertThat(result.get("status")).isEqualTo("ok");

        Path written = tempDir.resolve(testPath);
        assertThat(Files.readString(written)).isEqualTo(testContent);
    }

    @Test
    void putFileContent_createsParentDirectories() throws IOException {
        String testPath = "nested/dir/memory.md";
        String testContent = "# Nested";

        controller.putFileContent(testPath, null, testContent);

        Path written = tempDir.resolve(testPath);
        assertThat(Files.readString(written)).isEqualTo(testContent);
    }

    @Test
    void putFileContent_preventsPathTraversal() {
        assertThatThrownBy(() -> controller.putFileContent("../../etc/passwd", null, "bad content"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    var ex = (ResponseStatusException) e;
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                });
    }
}
