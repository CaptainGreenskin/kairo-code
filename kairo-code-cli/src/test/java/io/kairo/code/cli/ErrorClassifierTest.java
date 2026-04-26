package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import org.junit.jupiter.api.Test;

class ErrorClassifierTest {

    @Test
    void networkTimeoutIsRetryable() {
        assertThat(ErrorClassifier.isRetryable(new SocketTimeoutException("timeout"))).isTrue();
    }

    @Test
    void connectExceptionIsRetryable() {
        assertThat(ErrorClassifier.isRetryable(new ConnectException("refused"))).isTrue();
    }

    @Test
    void rateLimitMessageIsRetryable() {
        assertThat(ErrorClassifier.isRetryable(
                new RuntimeException("429 rate limit exceeded"))).isTrue();
    }

    @Test
    void missingFileIsNotRetryable() {
        assertThat(ErrorClassifier.isRetryable(new NoSuchFileException("/path"))).isFalse();
    }

    @Test
    void accessDeniedIsNotRetryable() {
        assertThat(ErrorClassifier.isRetryable(new AccessDeniedException("/path"))).isFalse();
    }

    @Test
    void unauthorizedMessageIsNotRetryable() {
        assertThat(ErrorClassifier.isRetryable(
                new RuntimeException("401 unauthorized"))).isFalse();
    }

    @Test
    void nullIsNotRetryable() {
        assertThat(ErrorClassifier.isRetryable(null)).isFalse();
    }
}
