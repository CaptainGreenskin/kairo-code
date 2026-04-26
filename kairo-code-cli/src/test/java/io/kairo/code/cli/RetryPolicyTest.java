package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.*;

import java.net.ConnectException;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    // Use 0 ms delay so tests don't wait for real backoff
    private RetryPolicy policy(int maxRetries) {
        return new RetryPolicy(maxRetries, 0L);
    }

    @Test
    void succeedsOnFirstAttemptWithNoRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        int result = policy(0).execute(() -> { calls.incrementAndGet(); return 42; });

        assertThat(result).isEqualTo(42);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void retriesOnRetryableErrorAndEventuallySucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String result = policy(2).execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new ConnectException("connection refused");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3); // 1 original + 2 retries
    }

    @Test
    void maxRetriesZeroDoesNotRetry() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> policy(0).execute(() -> {
            calls.incrementAndGet();
            throw new ConnectException("fail");
        })).isInstanceOf(ConnectException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void nonRetryableErrorFailsImmediately() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> policy(3).execute(() -> {
            calls.incrementAndGet();
            throw new NoSuchFileException("/missing/file");
        })).isInstanceOf(NoSuchFileException.class);

        assertThat(calls.get()).isEqualTo(1); // no retries consumed
    }

    @Test
    void exhaustsAllRetriesAndThrowsLastException() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> policy(2).execute(() -> {
            calls.incrementAndGet();
            throw new ConnectException("always fails attempt " + calls.get());
        })).isInstanceOf(ConnectException.class)
                .hasMessageContaining("3"); // last attempt message

        assertThat(calls.get()).isEqualTo(3); // 1 + 2 retries
    }

    @Test
    void illegalArgumentForNegativeMaxRetries() {
        assertThatThrownBy(() -> new RetryPolicy(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void illegalArgumentForMaxRetriesAboveFive() {
        assertThatThrownBy(() -> new RetryPolicy(6))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
