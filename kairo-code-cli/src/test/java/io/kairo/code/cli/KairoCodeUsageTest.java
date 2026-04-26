package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Tests for KairoCodeMain --show-usage option. Only covers validation paths that exit before any
 * network call.
 */
class KairoCodeUsageTest {

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

    @Test
    void showUsageOptionAcceptedByParser() {
        // --show-usage is parsed correctly (exits 1 due to fake key, not 2 for parse error)
        int code = run("--api-key", "fake", "--task", "t", "--show-usage");
        assertThat(code).isNotEqualTo(2);
        assertThat(errCapture.toString()).doesNotContain("Unknown option");
    }

    @Test
    void withoutShowUsageNoUsageLine() {
        int code = run("--api-key", "fake", "--task", "t");
        // No [USAGE] line when flag is absent
        assertThat(errCapture.toString()).doesNotContain("[USAGE]");
    }

    @Test
    void showUsageCombinesWithVerbose() {
        int code = run("--api-key", "fake", "--task", "t", "--show-usage", "--verbose");
        assertThat(code).isNotEqualTo(2);
        assertThat(errCapture.toString()).doesNotContain("Unknown option");
    }

    @Test
    void showUsageCombinesWithTimeout() {
        int code = run("--api-key", "fake", "--task", "t", "--show-usage", "--timeout", "60");
        assertThat(code).isNotEqualTo(2);
        assertThat(errCapture.toString()).doesNotContain("Unknown option");
    }
}
