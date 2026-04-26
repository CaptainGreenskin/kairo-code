package io.kairo.code.cli;

/**
 * Classifies exceptions into retryable and non-retryable categories.
 *
 * <p>Non-retryable errors fail immediately without consuming retry budget:
 * invalid input, missing files, authentication failures.
 */
public final class ErrorClassifier {

    private ErrorClassifier() {}

    public static boolean isRetryable(Throwable t) {
        if (t == null) return false;
        String msg = t.getMessage() != null ? t.getMessage().toLowerCase() : "";

        // Non-retryable: file/config problems
        if (t instanceof java.nio.file.NoSuchFileException) return false;
        if (t instanceof java.nio.file.AccessDeniedException) return false;
        if (t instanceof IllegalArgumentException) return false;

        // Non-retryable: auth failures (look for 401/403 in message)
        if (msg.contains("401") || msg.contains("unauthorized")) return false;
        if (msg.contains("403") || msg.contains("forbidden")) return false;

        // Retryable: network and rate-limit issues
        if (t instanceof java.net.SocketTimeoutException) return true;
        if (t instanceof java.net.ConnectException) return true;
        if (msg.contains("429") || msg.contains("rate limit")) return true;
        if (msg.contains("timeout") || msg.contains("timed out")) return true;
        if (msg.contains("503") || msg.contains("502") || msg.contains("504")) return true;

        // Default: retryable (unknown errors may be transient)
        return true;
    }
}
