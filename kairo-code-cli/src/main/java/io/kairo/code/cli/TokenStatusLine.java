package io.kairo.code.cli;

/**
 * Formats a persistent token-usage status line displayed below the REPL prompt.
 *
 * <p>The status line shows cumulative token usage relative to the model's context window limit,
 * color-coded by utilisation band:
 * <ul>
 *   <li><b>Gray</b> ({@code <80%}) — normal</li>
 *   <li><b>Yellow</b> ({@code 80–89%}) — approaching limit</li>
 *   <li><b>Red</b> ({@code ≥90%}) — danger zone</li>
 * </ul>
 *
 * <p>An optional compact-phase label (e.g. "Snip", "Micro") is appended when context compaction
 * is active.
 */
public final class TokenStatusLine {

    // ANSI color codes
    static final String GRAY = "\033[90m";
    static final String YELLOW = "\033[33m";
    static final String RED = "\033[31m";
    static final String RESET = "\033[0m";

    private TokenStatusLine() {}

    /**
     * Format the token status line with appropriate color.
     *
     * @param usedTokens   current cumulative input tokens
     * @param limitTokens  model context window limit (must be &gt; 0)
     * @param compactPhase current compaction phase label, or {@code null} if none
     * @return formatted ANSI string, or empty string if {@code usedTokens == 0}
     */
    public static String format(long usedTokens, long limitTokens, String compactPhase) {
        if (usedTokens == 0) {
            return "";
        }

        double ratio = limitTokens > 0 ? (double) usedTokens / limitTokens : 0;
        String color;
        if (ratio >= 0.9) {
            color = RED;
        } else if (ratio >= 0.8) {
            color = YELLOW;
        } else {
            color = GRAY;
        }

        String tokensStr = formatK(usedTokens) + "/" + formatK(limitTokens);
        String compactStr = compactPhase != null ? " | compact: " + compactPhase : "";

        return color + "[tokens: " + tokensStr + compactStr + "]" + RESET;
    }

    /**
     * Human-friendly token count: values ≥ 1000 are rendered as {@code Nk} (integer division),
     * values below 1000 are rendered as-is.
     */
    static String formatK(long tokens) {
        if (tokens >= 1000) {
            return (tokens / 1000) + "k";
        }
        return String.valueOf(tokens);
    }

    /**
     * Determine the context window token limit for a given model name.
     *
     * @param modelName the model identifier (e.g. "claude-sonnet-4-20250514", "gpt-4o")
     * @return the context window size in tokens
     */
    public static int contextLimitForModel(String modelName) {
        if (modelName == null) {
            return 128_000;
        }
        String lower = modelName.toLowerCase();
        if (lower.contains("claude")) {
            return 200_000;
        }
        if (lower.contains("gpt-4o") || lower.contains("gpt-4-turbo")) {
            return 128_000;
        }
        if (lower.contains("glm")) {
            return 128_000;
        }
        return 128_000;
    }
}
