package io.kairo.code.server;

import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.controller.GitController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link GitController} status/diff endpoints.
 */
class GitControllerTest {

    @TempDir
    Path tempDir;

    private GitController controller;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        run("git", "init", tempDir.toString());
        run("git", "-C", tempDir.toString(), "config", "user.email", "test@test.com");
        run("git", "-C", tempDir.toString(), "config", "user.name", "Test");
        run("git", "-C", tempDir.toString(), "config", "commit.gpgsign", "false");

        ServerProperties props = new ServerProperties(
                "openai", "gpt-4o", tempDir.toString(),
                "https://api.openai.com", "sk-test");
        controller = new GitController(props);
    }

    @Test
    void getStatus_returnsEmptyForCleanRepo() throws Exception {
        Files.writeString(tempDir.resolve("init.txt"), "init");
        run("git", "-C", tempDir.toString(), "add", ".");
        run("git", "-C", tempDir.toString(), "commit", "-m", "init");

        List<Map<String, String>> status = controller.getStatus();
        assertThat(status).isEmpty();
    }

    @Test
    void getStatus_detectsUntrackedFile() throws Exception {
        Files.writeString(tempDir.resolve("new.txt"), "content");

        List<Map<String, String>> status = controller.getStatus();
        assertThat(status)
                .anyMatch(m -> "??".equals(m.get("status")) && m.get("path").contains("new.txt"));
    }

    @Test
    void getStatus_detectsModifiedFile() throws Exception {
        Path file = tempDir.resolve("init.txt");
        Files.writeString(file, "init");
        run("git", "-C", tempDir.toString(), "add", ".");
        run("git", "-C", tempDir.toString(), "commit", "-m", "init");

        Files.writeString(file, "modified content");

        List<Map<String, String>> status = controller.getStatus();
        assertThat(status)
                .anyMatch(m -> "M".equals(m.get("status")) && "init.txt".equals(m.get("path")));
    }

    @Test
    void getDiff_returnsEmptyForCleanState() throws Exception {
        Files.writeString(tempDir.resolve("init.txt"), "init");
        run("git", "-C", tempDir.toString(), "add", ".");
        run("git", "-C", tempDir.toString(), "commit", "-m", "init");

        Map<String, String> diff = controller.getDiff("");
        assertThat(diff.get("diff")).isBlank();
        assertThat(diff.get("truncated")).isEqualTo("false");
    }

    @Test
    void getDiff_returnsContentForModifiedFile() throws Exception {
        Path file = tempDir.resolve("init.txt");
        Files.writeString(file, "alpha\n");
        run("git", "-C", tempDir.toString(), "add", ".");
        run("git", "-C", tempDir.toString(), "commit", "-m", "init");

        Files.writeString(file, "beta\n");

        Map<String, String> diff = controller.getDiff("init.txt");
        assertThat(diff.get("diff")).contains("-alpha").contains("+beta");
        assertThat(diff.get("truncated")).isEqualTo("false");
    }

    private void run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor();
    }
}
