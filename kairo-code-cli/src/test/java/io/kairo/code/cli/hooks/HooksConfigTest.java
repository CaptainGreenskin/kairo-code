package io.kairo.code.cli.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HooksConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void loadNonExistentFileReturnsEmpty() {
        HooksConfig config = HooksConfig.load(tempDir.resolve("nonexistent.json"));
        assertThat(config.isEmpty()).isTrue();
        assertThat(config.getHooks("PreToolUse")).isEmpty();
    }

    @Test
    void parsesValidJson() throws IOException {
        String json = """
                {
                  "hooks": {
                    "PreToolUse": [
                      { "matcher": "bash", "command": "echo 'pre bash'" }
                    ],
                    "PostToolUse": [
                      { "matcher": "*", "command": "echo 'tool done: {{tool_name}}'" }
                    ],
                    "Stop": [
                      { "matcher": "*", "command": "echo 'stopped'" }
                    ]
                  }
                }
                """;

        HooksConfig config = HooksConfig.parse(json);

        assertThat(config.isEmpty()).isFalse();

        List<HookEntry> preToolUse = config.getHooks("PreToolUse");
        assertThat(preToolUse).hasSize(1);
        assertThat(preToolUse.get(0).matcher()).isEqualTo("bash");
        assertThat(preToolUse.get(0).command()).isEqualTo("echo 'pre bash'");

        List<HookEntry> postToolUse = config.getHooks("PostToolUse");
        assertThat(postToolUse).hasSize(1);
        assertThat(postToolUse.get(0).matcher()).isEqualTo("*");
        assertThat(postToolUse.get(0).command()).contains("{{tool_name}}");

        List<HookEntry> stop = config.getHooks("Stop");
        assertThat(stop).hasSize(1);
        assertThat(stop.get(0).command()).isEqualTo("echo 'stopped'");
    }

    @Test
    void multipleHooksPerEvent() {
        String json = """
                {
                  "hooks": {
                    "PreToolUse": [
                      { "matcher": "bash", "command": "echo 'first'" },
                      { "matcher": "*", "command": "echo 'wildcard'" }
                    ]
                  }
                }
                """;

        HooksConfig config = HooksConfig.parse(json);
        List<HookEntry> hooks = config.getHooks("PreToolUse");
        assertThat(hooks).hasSize(2);
        assertThat(hooks.get(0).matcher()).isEqualTo("bash");
        assertThat(hooks.get(1).matcher()).isEqualTo("*");
    }

    @Test
    void emptyCommandEntriesAreSkipped() {
        String json = """
                {
                  "hooks": {
                    "PostToolUse": [
                      { "matcher": "*", "command": "" },
                      { "matcher": "*", "command": "echo 'valid'" }
                    ]
                  }
                }
                """;

        HooksConfig config = HooksConfig.parse(json);
        assertThat(config.getHooks("PostToolUse")).hasSize(1);
        assertThat(config.getHooks("PostToolUse").get(0).command()).isEqualTo("echo 'valid'");
    }

    @Test
    void missingMatcherDefaultsToWildcard() {
        String json = """
                {
                  "hooks": {
                    "Stop": [
                      { "command": "echo 'no matcher'" }
                    ]
                  }
                }
                """;

        HooksConfig config = HooksConfig.parse(json);
        List<HookEntry> hooks = config.getHooks("Stop");
        assertThat(hooks).hasSize(1);
        assertThat(hooks.get(0).matcher()).isEqualTo("*");
    }

    @Test
    void missingHooksKeyReturnsEmpty() {
        String json = "{}";
        HooksConfig config = HooksConfig.parse(json);
        assertThat(config.isEmpty()).isTrue();
    }

    @Test
    void invalidJsonReturnsEmpty() {
        HooksConfig config = HooksConfig.parse("not json");
        assertThat(config.isEmpty()).isTrue();
    }

    @Test
    void hookEntryMatchesExact() {
        HookEntry entry = new HookEntry("bash", "echo test");
        assertThat(entry.matches("bash")).isTrue();
        assertThat(entry.matches("read")).isFalse();
    }

    @Test
    void hookEntryWildcardMatchesAll() {
        HookEntry entry = new HookEntry("*", "echo test");
        assertThat(entry.matches("bash")).isTrue();
        assertThat(entry.matches("read")).isTrue();
        assertThat(entry.matches("anything")).isTrue();
    }
}
