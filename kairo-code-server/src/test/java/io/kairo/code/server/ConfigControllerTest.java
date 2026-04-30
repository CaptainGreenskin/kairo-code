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
        controller = new ConfigController(props, null, null);
    }

    @Test
    void listFiles_returnsRootEntries() {
        var entries = controller.listFiles("");

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
        var entries = controller.listFiles("subdir");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).name()).isEqualTo("nested.py");
        assertThat(entries.get(0).path()).isEqualTo("subdir/nested.py");
    }

    @Test
    void listFiles_preventsPathTraversal() {
        assertThatThrownBy(() -> controller.listFiles("../../etc"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    var ex = (ResponseStatusException) e;
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                });
    }

    @Test
    void listFiles_nonExistentDirectory_returns404() {
        assertThatThrownBy(() -> controller.listFiles("nonexistent"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    var ex = (ResponseStatusException) e;
                    assertThat(ex.getStatusCode().value()).isEqualTo(404);
                });
    }

    @Test
    void getFileContent_returnsContent() {
        var response = controller.getFileContent("hello.txt");

        assertThat(response.path()).isEqualTo("hello.txt");
        assertThat(response.content()).isEqualTo("Hello, World!");
        assertThat(response.language()).isEqualTo("");

        var javaResponse = controller.getFileContent("test.java");
        assertThat(javaResponse.content()).isEqualTo("public class Test {}");
        assertThat(javaResponse.language()).isEqualTo("java");

        var pyResponse = controller.getFileContent("subdir/nested.py");
        assertThat(pyResponse.content()).isEqualTo("print('hello')");
        assertThat(pyResponse.language()).isEqualTo("python");
    }

    @Test
    void getFileContent_rejectsTooLarge() throws IOException {
        // Create a file larger than 100KB
        Path largeFile = tempDir.resolve("large.bin");
        byte[] data = new byte[100_001];
        Files.write(largeFile, data);

        assertThatThrownBy(() -> controller.getFileContent("large.bin"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    var ex = (ResponseStatusException) e;
                    assertThat(ex.getStatusCode().value()).isEqualTo(413);
                });
    }

    @Test
    void getFileContent_preventsPathTraversal() {
        assertThatThrownBy(() -> controller.getFileContent("../../etc/passwd"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    var ex = (ResponseStatusException) e;
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                });
    }

    @Test
    void getFileContent_nonExistentFile_returns404() {
        assertThatThrownBy(() -> controller.getFileContent("nonexistent.txt"))
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

        var result = controller.putFileContent(testPath, testContent);

        assertThat(result.get("status")).isEqualTo("ok");

        Path written = tempDir.resolve(testPath);
        assertThat(Files.readString(written)).isEqualTo(testContent);
    }

    @Test
    void putFileContent_createsParentDirectories() throws IOException {
        String testPath = "nested/dir/memory.md";
        String testContent = "# Nested";

        controller.putFileContent(testPath, testContent);

        Path written = tempDir.resolve(testPath);
        assertThat(Files.readString(written)).isEqualTo(testContent);
    }

    @Test
    void putFileContent_preventsPathTraversal() {
        assertThatThrownBy(() -> controller.putFileContent("../../etc/passwd", "bad content"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    var ex = (ResponseStatusException) e;
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                });
    }
}
