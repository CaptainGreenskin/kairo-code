package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Tests for KairoCodeMain --provider option validation. Only tests paths that exit before any
 * network call.
 */
class KairoCodeProviderTest {

    private final PrintStream originalErr = System.err;
    private ByteArrayOutputStream errCapture;

    @BeforeEach
    void captureStderr() {
        errCapture = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errCapture));
    }

    @AfterEach
    void restoreStderr() {
        System.setErr(originalErr);
    }

    private int run(String... args) {
        return new CommandLine(new KairoCodeMain()).execute(args);
    }

    private String capturedErr() {
        return errCapture.toString();
    }

    @Test
    void unknownProviderReturnsExitCode1() {
        int code = run("--api-key", "fake", "--task", "t", "--provider", "grok");
        assertThat(code).isEqualTo(1);
        assertThat(capturedErr()).contains("unknown provider").contains("grok");
    }

    @Test
    void providerOpenaiIsValid() {
        // valid provider, exits 1 because fake api key causes agent failure — not provider error
        int code = run("--api-key", "fake", "--task", "t", "--provider", "openai");
        assertThat(capturedErr()).doesNotContain("unknown provider");
    }

    @Test
    void providerAnthropicIsValid() {
        int code = run("--api-key", "fake", "--task", "t", "--provider", "anthropic");
        assertThat(capturedErr()).doesNotContain("unknown provider");
    }

    @Test
    void providerQianwenIsValid() {
        int code = run("--api-key", "fake", "--task", "t", "--provider", "qianwen");
        assertThat(capturedErr()).doesNotContain("unknown provider");
    }

    @Test
    void providerCaseInsensitive() {
        int code = run("--api-key", "fake", "--task", "t", "--provider", "OPENAI");
        assertThat(capturedErr()).doesNotContain("unknown provider");
    }
}
