/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.kairo.code.cli.statusline;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Integration tests for {@link ShellStatusLineRenderer}.
 *
 * <p>These tests spawn real subshells (`/bin/sh -c ...`) and so are POSIX-only. CI on Linux/macOS
 * runs them; Windows CI skips via {@link EnabledOnOs}. The commands chosen ({@code echo},
 * {@code cat}, {@code printf}, {@code false}, {@code sleep}) are present on every POSIX system.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class ShellStatusLineRendererTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ShellStatusLineRenderer renderer = new ShellStatusLineRenderer(mapper);

    private static StatusLineConfig cfg(String cmd) {
        return new StatusLineConfig(
                "command", cmd, Duration.ZERO, 0, Duration.ofSeconds(5));
    }

    private static StatusLineConfig cfgWithTimeout(String cmd, Duration timeout) {
        return new StatusLineConfig("command", cmd, Duration.ZERO, 0, timeout);
    }

    private static StatusLineState minimalState() {
        return new StatusLineState(
                "sess-1",
                null,
                new StatusLineState.ModelInfo("m-1", "Model 1"),
                new StatusLineState.WorkspaceInfo("/tmp", "/tmp"),
                "0.2.0",
                StatusLineState.ContextWindowInfo.from(1000, 200_000, null),
                null);
    }

    @Test
    void disabledConfig_returnsEmpty() {
        assertThat(renderer.render(StatusLineConfig.disabled(), minimalState())).isEmpty();
    }

    @Test
    void echoCommand_returnsTrimmedStdout() {
        String out = renderer.render(cfg("echo hello-world"), minimalState());
        assertThat(out).isEqualTo("hello-world");
    }

    @Test
    void scriptCanReadJsonFromStdin() {
        // Most realistic test: a shell pipeline that consumes the JSON state from stdin and
        // extracts a field. Avoid `jq` since CI might not have it — use `cat` + plain text.
        // The renderer's only job is "JSON → stdin"; the script reads it back verbatim and
        // greps for a literal field name we know is present.
        String out = renderer.render(cfg("cat | grep -o sess-1 | head -1"), minimalState());
        assertThat(out).isEqualTo("sess-1");
    }

    @Test
    void nonZeroExit_returnsEmpty_andDoesNotThrow() {
        // `false` exits 1 with no stdout — renderer must swallow this gracefully so a broken
        // user script doesn't crash the REPL.
        assertThat(renderer.render(cfg("false"), minimalState())).isEmpty();
    }

    @Test
    void emptyStdout_returnsEmpty() {
        // `true` exits 0 with no output — semantically equivalent to "clear the status line".
        assertThat(renderer.render(cfg("true"), minimalState())).isEmpty();
    }

    @Test
    void timeoutKillsLongRunningCommand() {
        long start = System.currentTimeMillis();
        String out =
                renderer.render(
                        cfgWithTimeout("sleep 5", Duration.ofMillis(300)), minimalState());
        long elapsed = System.currentTimeMillis() - start;
        assertThat(out).isEmpty();
        // Sanity check: we killed the process well before the 5s sleep would have completed.
        // The bound is generous to avoid CI flake.
        assertThat(elapsed).isLessThan(3_000);
    }

    @Test
    void multilineOutput_isJoinedAndBlankLinesDropped() {
        // printf -e style: emit 3 lines with a blank one in the middle. Cleanup drops the blank
        // and re-joins with \n. Documents the "single line recommended but multi-line allowed"
        // contract from the spec.
        String out = renderer.render(cfg("printf 'line1\\n\\nline2\\n'"), minimalState());
        assertThat(out).isEqualTo("line1\nline2");
    }

    @Test
    void cleanOutput_handlesNullAndBlanks() {
        // Direct unit test on the static cleaner.
        assertThat(ShellStatusLineRenderer.cleanOutput(null)).isEmpty();
        assertThat(ShellStatusLineRenderer.cleanOutput("")).isEmpty();
        assertThat(ShellStatusLineRenderer.cleanOutput("   \n  \n  ")).isEmpty();
        assertThat(ShellStatusLineRenderer.cleanOutput("  hello  ")).isEqualTo("hello");
        assertThat(ShellStatusLineRenderer.cleanOutput("a\n\nb\n")).isEqualTo("a\nb");
    }

    @Test
    void brokenCommand_returnsEmpty() {
        // A command that doesn't exist on PATH — the shell exits 127. Renderer must treat
        // this like any other non-zero exit and not throw.
        assertThat(renderer.render(cfg("__definitely_not_a_real_binary__"), minimalState())).isEmpty();
    }

    @Test
    void state_serializesAsJson_withExpectedFields() throws Exception {
        // Round-trip: serialise the state we'd send and verify the field names the user's
        // script will jq for actually appear. This locks the wire format.
        StatusLineState state = minimalState();
        String json = mapper.writeValueAsString(state);
        assertThat(json).contains("\"sessionId\":\"sess-1\"");
        assertThat(json).contains("\"model\":{");
        assertThat(json).contains("\"displayName\":\"Model 1\"");
        assertThat(json).contains("\"contextWindow\":{");
        assertThat(json).contains("\"totalInputTokens\":1000");
        assertThat(json).contains("\"contextWindowSize\":200000");
        // Null-valued fields (sessionName, agent) are stripped by the @JsonInclude annotation.
        assertThat(json).doesNotContain("sessionName");
        assertThat(json).doesNotContain("\"agent\":");
    }

    @Test
    void contextWindowInfo_from_computesPercentages() {
        StatusLineState.ContextWindowInfo info =
                StatusLineState.ContextWindowInfo.from(50_000, 200_000, "snip");
        assertThat(info.usedPercentage()).isEqualTo(25.0);
        assertThat(info.remainingPercentage()).isEqualTo(75.0);
        assertThat(info.compactionPhase()).isEqualTo("snip");
    }

    @Test
    void contextWindowInfo_zeroWindow_doesNotDivideByZero() {
        // Unknown model → context window size 0. Avoid NaN / Infinity in JSON output that would
        // crash user scripts doing `printf %d`.
        StatusLineState.ContextWindowInfo info =
                StatusLineState.ContextWindowInfo.from(123, 0, null);
        assertThat(info.usedPercentage()).isEqualTo(0.0);
        assertThat(info.remainingPercentage()).isEqualTo(0.0);
    }
}
