package io.kairo.code.server;

import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.controller.ConfigController;
import io.kairo.code.server.dto.SearchMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ConfigController} search endpoint.
 */
class SearchControllerTest {

    @TempDir
    Path tempDir;

    private ConfigController controller;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(tempDir.resolve("hello.txt"), "Hello, World!\nThis is a test file.\nSay hello again.");
        Files.writeString(tempDir.resolve("test.java"), "public class Test {\n    public static void main(String[] args) {\n        System.out.println(\"hello\");\n    }\n}");

        Path srcDir = tempDir.resolve("src");
        Files.createDirectory(srcDir);
        Files.writeString(srcDir.resolve("App.tsx"), "const [fileTreeOpen, setFileTreeOpen] = useState(false);\nsetFileTreeOpen(false);");

        ServerProperties props = new ServerProperties(
                "openai", "gpt-4o", tempDir.toString(),
                "https://api.openai.com", "sk-test");
        controller = new ConfigController(props, null, null, null);
    }

    @Test
    void search_findsMatchInFile() {
        var response = controller.searchFiles("hello", "", 50, null);

        assertThat(response.query()).isEqualTo("hello");
        assertThat(response.matches()).isNotEmpty();
        assertThat(response.truncated()).isFalse();

        var helloMatches = response.matches().stream()
                .filter(m -> m.file().equals("hello.txt"))
                .toList();
        assertThat(helloMatches).hasSize(2);
        assertThat(helloMatches.get(0).preview()).containsIgnoringCase("hello");
    }

    @Test
    void search_preventsPathTraversal() {
        assertThatThrownBy(() -> controller.searchFiles("test", "../../etc", 50, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    var ex = (ResponseStatusException) e;
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                });
    }

    @Test
    void search_skipsNodeModules() throws IOException {
        Path nm = tempDir.resolve("node_modules");
        Files.createDirectory(nm);
        Files.writeString(nm.resolve("dep.js"), "function hello() { return 'hello'; }");

        var response = controller.searchFiles("hello", "", 50, null);

        var nodeModulesMatches = response.matches().stream()
                .filter(m -> m.file().startsWith("node_modules"))
                .toList();
        assertThat(nodeModulesMatches).isEmpty();
    }

    @Test
    void search_respectsLimit() throws IOException {
        // Create many files with the search term
        for (int i = 0; i < 20; i++) {
            Files.writeString(tempDir.resolve("file" + i + ".txt"), "match line " + i);
        }

        var response = controller.searchFiles("match", "", 5, null);

        assertThat(response.matches()).hasSize(5);
        assertThat(response.truncated()).isTrue();
    }

    @Test
    void search_caseInsensitive() {
        var response = controller.searchFiles("HELLO", "", 50, null);

        assertThat(response.matches()).isNotEmpty();
        var helloMatches = response.matches().stream()
                .filter(m -> m.file().equals("hello.txt"))
                .toList();
        assertThat(helloMatches).hasSize(2);
    }

    @Test
    void search_shortQueryReturnsEmpty() {
        var response = controller.searchFiles("a", "", 50, null);
        assertThat(response.matches()).isEmpty();
    }

    @Test
    void search_findsInSubdirectory() {
        var response = controller.searchFiles("fileTreeOpen", "", 50, null);

        var appMatches = response.matches().stream()
                .filter(m -> m.file().equals("src/App.tsx"))
                .toList();
        assertThat(appMatches).isNotEmpty();
        assertThat(appMatches.get(0).line()).isEqualTo(1);
    }
}
