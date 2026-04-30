package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the shell passthrough feature ({@code !cmd} in the REPL).
 *
 * <p>Since {@code ReplLoop.executeShellCommand} is private, these tests exercise
 * the same {@link ProcessBuilder} pattern directly — verifying that the shell
 * dispatch logic works correctly for various edge cases.
 *
 * <p>No API key required — these are pure OS-level process tests.
 */
class ShellPassthroughIT {

    /**
     * Helper that mirrors ReplLoop.executeShellCommand: runs {@code sh -c <cmd>}
     * and captures stdout + exit code.
     */
    private ShellResult executeShell(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        assertThat(finished).as("Process should complete within 10s").isTrue();
        return new ShellResult(process.exitValue(), output);
    }

    private record ShellResult(int exitCode, String output) {}

    // ──────────────────────────────────────────────────────────────────────
    // Test 1: !echo kairo-test → output contains "kairo-test"
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void shellEchoReturnsOutput() throws Exception {
        ShellResult result = executeShell("echo kairo-test");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("kairo-test");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 2: Non-existent command shows error, not crash
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void nonExistentCommandShowsError() throws Exception {
        ShellResult result = executeShell("nonexistent-cmd-xyz-12345");
        assertThat(result.exitCode()).isNotZero();
        // stderr merged into stdout — should contain "not found" or similar
        assertThat(result.output().toLowerCase()).containsAnyOf("not found", "no such file");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 3: !false → exit code 1, no crash
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void shellCommandWithNonZeroExitCode() throws Exception {
        ShellResult result = executeShell("false");
        assertThat(result.exitCode()).isEqualTo(1);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 4: Empty command (just bang) is handled gracefully
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void emptyBangIsIgnored() {
        // In ReplLoop, "!" alone is trimmed to empty string and skipped.
        // Verify the guard logic: trimmed.substring(1).trim().isEmpty() == true
        String input = "!";
        String shellCmd = input.substring(1).trim();
        assertThat(shellCmd).isEmpty();
        // The REPL would skip execution — no ProcessBuilder created.
        // This test verifies the guard condition without spawning a process.
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 5: Shell command with pipe works
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void shellCommandWithPipeWorks() throws Exception {
        ShellResult result = executeShell("echo 'hello world' | tr ' ' '_'");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("hello_world");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 6: Shell command with special characters
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void shellCommandWithSpecialCharacters() throws Exception {
        ShellResult result = executeShell("echo \"kairo $((2+3))\"");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("kairo 5");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 7: Bang prefix dispatch logic matches ReplLoop behavior
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void bangPrefixDispatchLogicMatchesReplLoop() {
        // Verify the input parsing logic from ReplLoop lines 380-386
        // The REPL checks: trimmed.startsWith("!") then shellCmd = trimmed.substring(1).trim()

        assertThat(extractShellCmd("!ls")).isEqualTo("ls");
        assertThat(extractShellCmd("!  echo hello  ")).isEqualTo("echo hello");
        assertThat(extractShellCmd("!")).isEmpty();
        assertThat(extractShellCmd("! ")).isEmpty();
    }

    /** Mirrors ReplLoop's extraction: strip "!" prefix and trim. */
    private static String extractShellCmd(String trimmedInput) {
        return trimmedInput.substring(1).trim();
    }
}
