package io.kairo.code.cli;

import java.util.concurrent.Callable;

/**
 * Executes a callable with exponential-backoff retry.
 *
 * <p>Delays: 1 s → 2 s → 4 s … up to {@code maxRetries} additional attempts.
 * Non-retryable exceptions (per {@link ErrorClassifier}) propagate immediately.
 */
public final class RetryPolicy {

    private final int maxRetries;
    private final long initialDelayMs;

    public RetryPolicy(int maxRetries) {
        this(maxRetries, 1_000L);
    }

    /** Package-private: lets tests inject a short delay. */
    RetryPolicy(int maxRetries, long initialDelayMs) {
        if (maxRetries < 0 || maxRetries > 5) {
            throw new IllegalArgumentException("maxRetries must be between 0 and 5");
        }
        this.maxRetries = maxRetries;
        this.initialDelayMs = initialDelayMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * Executes {@code task}, retrying on retryable failures up to {@code maxRetries} times.
     *
     * @return the result of {@code task}
     * @throws Exception the last exception if all attempts fail
     */
    public <T> T execute(Callable<T> task) throws Exception {
        Exception last = null;
        long delay = initialDelayMs;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return task.call();
            } catch (Exception e) {
                last = e;
                if (!ErrorClassifier.isRetryable(e) || attempt == maxRetries) {
                    throw e;
                }
                System.err.printf("重试第 %d 次（共 %d 次）：%s%n",
                        attempt + 1, maxRetries, e.getMessage());
                sleep(delay);
                delay *= 2;
            }
        }
        throw last;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
