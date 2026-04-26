package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Tests for --timeout option and TimeoutException handling in KairoCodeMain.
 * Only tests validation paths that do not make network calls.
 */
class KairoCodeTimeoutTest {

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
    void timeoutZeroIsAccepted() {
        // --timeout 0 is valid (no timeout), exits 1 only because of fake API key
        int code = run("--api-key", "fake", "--task", "t", "--timeout", "0");
        assertThat(code).isNotEqualTo(2); // 2 = picocli parse error
    }

    @Test
    void timeoutPositiveIsAccepted() {
        // --timeout 30 is valid, exits 1 only because of fake API key failing at connection
        int code = run("--api-key", "fake", "--task", "t", "--timeout", "30");
        assertThat(code).isNotEqualTo(2);
    }

    @Test
    void timeoutExceptionIsNotRetryable() {
        assertThat(ErrorClassifier.isRetryable(new TimeoutException("task timed out"))).isFalse();
    }

    @Test
    void wrappedTimeoutExceptionIsNotRetryable() {
        RuntimeException wrapped = new RuntimeException(new TimeoutException("timeout"));
        assertThat(ErrorClassifier.isRetryable(wrapped)).isFalse();
    }

    @Test
    void socketTimeoutIsStillRetryable() {
        // SocketTimeoutException is a network timeout → retryable, unlike task-level TimeoutException
        assertThat(ErrorClassifier.isRetryable(
                new java.net.SocketTimeoutException("read timeout"))).isTrue();
    }
}
