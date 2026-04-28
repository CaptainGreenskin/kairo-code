package io.kairo.code.cli.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HookExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyConfigDoesNothing() {
        HookExecutor executor = new HookExecutor(new HooksConfig(Map.of()));
        executor.fire("PreToolUse", "bash", "ls");
        executor.shutdown();
        // No exception = success
    }

    @Test
    void exactMatcherFiresHook() throws Exception {
        Path outputFile = tempDir.resolve("exact.log");
        String json = """
                {
                  "hooks": {
                    "PreToolUse": [
                      { "matcher": "bash", "command": "echo exact_hit > %s" }
                    ]
                  }
                }
                """.formatted(outputFile);

        HooksConfig config = HooksConfig.parse(json);
        HookExecutor executor = new HookExecutor(config);

        executor.fire("PreToolUse", "bash", "ls");

        // Wait for async execution
        Thread.sleep(500);
        executor.shutdown();

        assertThat(Files.exists(outputFile)).isTrue();
        assertThat(Files.readString(outputFile).trim()).isEqualTo("exact_hit");
    }

    @Test
    void exactMatcherDoesNotFireForOtherTools() throws Exception {
        Path outputFile = tempDir.resolve("no_hit.log");
        String json = """
                {
                  "hooks": {
                    "PreToolUse": [
                      { "matcher": "bash", "command": "echo should_not_run > %s" }
                    ]
                  }
                }
                """.formatted(outputFile);

        HooksConfig config = HooksConfig.parse(json);
        HookExecutor executor = new HookExecutor(config);

        executor.fire("PreToolUse", "read", "file.txt");

        Thread.sleep(500);
        executor.shutdown();

        assertThat(Files.exists(outputFile)).isFalse();
    }

    @Test
    void wildcardMatcherFiresForAnyTool() throws Exception {
        Path outputFile = tempDir.resolve("wildcard.log");
        String json = """
                {
                  "hooks": {
                    "PostToolUse": [
                      { "matcher": "*", "command": "echo wildcard_hit > %s" }
                    ]
                  }
                }
                """.formatted(outputFile);

        HooksConfig config = HooksConfig.parse(json);
        HookExecutor executor = new HookExecutor(config);

        executor.fire("PostToolUse", "read", "content");

        Thread.sleep(500);
        executor.shutdown();

        assertThat(Files.exists(outputFile)).isTrue();
        assertThat(Files.readString(outputFile).trim()).isEqualTo("wildcard_hit");
    }

    @Test
    void placeholderReplacement() throws Exception {
        Path outputFile = tempDir.resolve("placeholders.log");
        String json = """
                {
                  "hooks": {
                    "PreToolUse": [
                      { "matcher": "*", "command": "echo '{{tool_name}}:{{tool_input}}' > %s" }
                    ]
                  }
                }
                """.formatted(outputFile);

        HooksConfig config = HooksConfig.parse(json);
        HookExecutor executor = new HookExecutor(config);

        executor.fire("PreToolUse", "bash", "ls -la");

        Thread.sleep(500);
        executor.shutdown();

        assertThat(Files.readString(outputFile).trim()).isEqualTo("bash:ls -la");
    }

    @Test
    void stopHookFires() throws Exception {
        Path outputFile = tempDir.resolve("stop.log");
        String json = """
                {
                  "hooks": {
                    "Stop": [
                      { "matcher": "*", "command": "echo stopped > %s" }
                    ]
                  }
                }
                """.formatted(outputFile);

        HooksConfig config = HooksConfig.parse(json);
        HookExecutor executor = new HookExecutor(config);

        executor.fire("Stop", "", "");

        Thread.sleep(500);
        executor.shutdown();

        assertThat(Files.exists(outputFile)).isTrue();
        assertThat(Files.readString(outputFile).trim()).isEqualTo("stopped");
    }

    @Test
    void bothExactAndWildcardFire() throws Exception {
        Path exactFile = tempDir.resolve("exact2.log");
        Path wildcardFile = tempDir.resolve("wildcard2.log");
        String json = """
                {
                  "hooks": {
                    "PreToolUse": [
                      { "matcher": "bash", "command": "echo exact > %s" },
                      { "matcher": "*", "command": "echo wildcard > %s" }
                    ]
                  }
                }
                """.formatted(exactFile, wildcardFile);

        HooksConfig config = HooksConfig.parse(json);
        HookExecutor executor = new HookExecutor(config);

        executor.fire("PreToolUse", "bash", "cmd");

        Thread.sleep(500);
        executor.shutdown();

        assertThat(Files.readString(exactFile).trim()).isEqualTo("exact");
        assertThat(Files.readString(wildcardFile).trim()).isEqualTo("wildcard");
    }

    @Test
    void failedCommandDoesNotThrow() {
        String json = """
                {
                  "hooks": {
                    "Stop": [
                      { "matcher": "*", "command": "/nonexistent/command_that_fails" }
                    ]
                  }
                }
                """;

        HooksConfig config = HooksConfig.parse(json);
        HookExecutor executor = new HookExecutor(config);

        // Should not throw — failures are logged at WARN
        executor.fire("Stop", "", "");

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdown();
    }
}
