package io.kairo.code.cli;

import io.kairo.api.hook.HookHandler;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.PostActingEvent;
import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.hook.PreActingEvent;
import io.kairo.api.hook.PreReasoningEvent;
import io.kairo.api.message.Content;
import io.kairo.api.model.ModelResponse;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Hook listener that prints agent activity (tool calls, model responses) to the terminal
 * during {@code agent.call()}.
 *
 * <p>Registered via {@code AgentBuilder.hook(new AgentEventPrinter(writer))}.
 * All output methods are {@code synchronized} to ensure thread-safety since hooks
 * may fire from Reactor scheduler threads.
 */
public class AgentEventPrinter {

    private static final int MAX_ARGS_LENGTH = 100;
    private static final int MAX_RESULT_LENGTH = 200;
    private static final int MAX_RESULT_LINES = 5;

    // ANSI color codes
    private static final String RESET;
    private static final String BOLD;
    private static final String DIM;
    private static final String CYAN;
    private static final String GREEN;
    private static final String RED;
    private static final String YELLOW;

    static {
        boolean color = supportsColor();
        RESET = color ? "\u001B[0m" : "";
        BOLD = color ? "\u001B[1m" : "";
        DIM = color ? "\u001B[2m" : "";
        CYAN = color ? "\u001B[36m" : "";
        GREEN = color ? "\u001B[32m" : "";
        RED = color ? "\u001B[31m" : "";
        YELLOW = color ? "\u001B[33m" : "";
    }

    private final PrintWriter writer;
    private final String prefix;
    private final boolean streamingText;
    private final ModelCallSpinner spinner;
    private final boolean verboseThinking;
    private final int maxContextTokens;

    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong(0);
    private final AtomicLong turnCount = new AtomicLong(0);

    public AgentEventPrinter(PrintWriter writer) {
        this(writer, "", false, null, false);
    }

    /**
     * Prefix-aware variant for child agents spawned via the {@code task} tool. Pass e.g. {@code
     * "[task:t-abc12345] "} so streaming output from the child is visually distinct from the
     * parent's. Pass an empty string for the parent.
     */
    public AgentEventPrinter(PrintWriter writer, String prefix) {
        this(writer, prefix, false, null, false);
    }

    /**
     * Full constructor. When {@code streamingText} is true, per-token text output is handled by a
     * separate consumer; {@link #onPostReasoning} skips printing the text body to avoid duplication.
     */
    public AgentEventPrinter(PrintWriter writer, String prefix, boolean streamingText) {
        this(writer, prefix, streamingText, null, false);
    }

    /**
     * Complete constructor with optional model call spinner and thinking display control.
     */
    public AgentEventPrinter(PrintWriter writer, String prefix, boolean streamingText,
                              ModelCallSpinner spinner, boolean verboseThinking) {
        this.writer = writer;
        this.prefix = prefix == null ? "" : prefix;
        this.streamingText = streamingText;
        this.spinner = spinner;
        this.verboseThinking = verboseThinking;
        this.maxContextTokens = readMaxContextTokens();
    }

    /**
     * Constructor that explicitly specifies maxContextTokens (useful for testing).
     */
    public AgentEventPrinter(PrintWriter writer, String prefix, boolean streamingText,
                              ModelCallSpinner spinner, boolean verboseThinking,
                              int maxContextTokens) {
        this.writer = writer;
        this.prefix = prefix == null ? "" : prefix;
        this.streamingText = streamingText;
        this.spinner = spinner;
        this.verboseThinking = verboseThinking;
        this.maxContextTokens = maxContextTokens;
    }

    private String linePrefix() {
        return prefix.isEmpty() ? "" : DIM + prefix + RESET;
    }

    @HookHandler(HookPhase.PRE_REASONING)
    public synchronized void onPreReasoning(PreReasoningEvent event) {
        if (spinner != null) {
            spinner.start();
        }
    }

    @HookHandler(HookPhase.PRE_ACTING)
    public synchronized void onPreActing(PreActingEvent event) {
        String argsSummary = summarizeArgs(event.input());
        if (argsSummary.isEmpty()) {
            writer.println(linePrefix() + YELLOW + "⚡ Running " + BOLD + event.toolName() + RESET + YELLOW + "..." + RESET);
        } else {
            writer.println(linePrefix() + YELLOW + "⚡ Running " + BOLD + event.toolName() + RESET
                    + DIM + "(" + argsSummary + ")" + RESET + YELLOW + "..." + RESET);
        }
        writer.flush();
    }

    @HookHandler(HookPhase.POST_ACTING)
    public synchronized void onPostActing(PostActingEvent event) {
        String content = event.result().content();
        if (content != null && !content.isBlank()) {
            String display = content.length() > MAX_RESULT_LENGTH
                    ? content.substring(0, MAX_RESULT_LENGTH) + "... [truncated]"
                    : content;
            if (event.result().isError()) {
                writer.println(linePrefix() + RED + "  ✗ " + event.toolName() + " failed: " + RESET + truncateLines(display, MAX_RESULT_LINES));
            } else {
                writer.println(linePrefix() + GREEN + "  ✓ " + event.toolName() + " completed" + RESET);
                String preview = truncateLines(display, MAX_RESULT_LINES);
                if (!preview.isBlank()) {
                    writer.println(linePrefix() + DIM + "    " + preview.replace("\n", "\n    ") + RESET);
                }
            }
        } else {
            if (event.result().isError()) {
                writer.println(linePrefix() + RED + "  ✗ " + event.toolName() + " failed" + RESET);
            } else {
                writer.println(linePrefix() + GREEN + "  ✓ " + event.toolName() + " completed" + RESET);
            }
        }
        writer.flush();
    }

    @HookHandler(HookPhase.POST_REASONING)
    public synchronized void onPostReasoning(PostReasoningEvent event) {
        if (spinner != null) {
            spinner.stop();
        }
        if (event.response() == null || event.response().contents() == null) {
            return;
        }

        // Display ThinkingContent blocks
        boolean hasThinking = event.response().contents().stream()
                .anyMatch(Content.ThinkingContent.class::isInstance);

        if (hasThinking) {
            long thinkingChars = event.response().contents().stream()
                    .filter(Content.ThinkingContent.class::isInstance)
                    .mapToLong(c -> ((Content.ThinkingContent) c).thinking().length())
                    .sum();
            writer.println(linePrefix() + DIM + "  ✦ thinking (" + thinkingChars + " chars)" + RESET);

            if (verboseThinking) {
                event.response().contents().stream()
                        .filter(Content.ThinkingContent.class::isInstance)
                        .map(c -> ((Content.ThinkingContent) c).thinking())
                        .forEach(t -> {
                            writer.println(linePrefix() + DIM + "  │ " + RESET
                                    + DIM + t.replace("\n", "\n" + linePrefix() + DIM + "  │ " + RESET));
                        });
            }
        }

        if (!streamingText) {
            // Block-output path: print full text here (no per-token consumer).
            String text = event.response().contents().stream()
                    .filter(Content.TextContent.class::isInstance)
                    .map(c -> ((Content.TextContent) c).text())
                    .collect(Collectors.joining());
            if (!text.isBlank()) {
                writer.println();
                if (prefix.isEmpty()) {
                    writer.println(text);
                } else {
                    String p = linePrefix();
                    for (String line : text.split("\n", -1)) {
                        writer.println(p + line);
                    }
                }
                writer.println();
            }
        } else {
            // Streaming path: text was already printed per-token; just add trailing newlines.
            writer.println();
            writer.println();
        }
        ModelResponse.Usage usage = event.response().usage();
        if (usage != null) {
            long cumInput = totalInputTokens.addAndGet(usage.inputTokens());
            long cumOutput = totalOutputTokens.addAndGet(usage.outputTokens());
            turnCount.incrementAndGet();

            int pct = (int) (cumInput * 100L / maxContextTokens);
            String fillBar = buildFillBar(pct, 20);
            String barColor = barColorForPct(pct);
            writer.printf(linePrefix() + DIM + "[model] in=%d out=%d cache_read=%d" + RESET
                    + "  " + barColor + "context: %s %d%%" + RESET + "%n",
                    usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(),
                    fillBar, pct);
        }
        writer.flush();
    }

    /**
     * Build a context fill bar like [████░░░░░░░░░░░░░░░░] based on percentage and width.
     */
    static String buildFillBar(int pct, int width) {
        int filled = Math.max(0, Math.min(width, (int) Math.round(pct / 100.0 * width)));
        int empty = width - filled;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < filled; i++) sb.append('\u2588');
        for (int i = 0; i < empty; i++) sb.append('\u2591');
        sb.append(']');
        return sb.toString();
    }

    private static String barColorForPct(int pct) {
        if (pct >= 85) return RED;
        if (pct >= 70) return YELLOW;
        return DIM;
    }

    /**
     * Print a session summary with cumulative token counts and elapsed time.
     * Called when the REPL exits (via :exit, Ctrl+D, or --print mode completion).
     */
    public synchronized void printSessionSummary(long elapsedMs) {
        long totalIn = totalInputTokens.get();
        long totalOut = totalOutputTokens.get();
        long totalTurns = turnCount.get();

        writer.println();
        writer.println(linePrefix() + DIM + "\u2500".repeat(40) + RESET);
        writer.printf(linePrefix() + "Session complete  " + DIM
                + "turns=%d  tokens in=%,d out=%,d  elapsed=%s" + RESET + "%n",
                totalTurns, totalIn, totalOut, formatDuration(elapsedMs));
        writer.println(linePrefix() + DIM + "\u2500".repeat(40) + RESET);
        writer.flush();
    }

    private static String formatDuration(long ms) {
        if (ms < 60_000) return (ms / 1000) + "s";
        long minutes = ms / 60_000;
        long seconds = (ms % 60_000) / 1000;
        return minutes + "m" + seconds + "s";
    }

    private static int readMaxContextTokens() {
        String env = System.getenv("KAIRO_CODE_MAX_CONTEXT_TOKENS");
        if (env != null && !env.isBlank()) {
            try { return Integer.parseInt(env.trim()); } catch (NumberFormatException ignored) {}
        }
        return 100_000;
    }

    private static String summarizeArgs(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String summary = input.entrySet().stream()
                .map(e -> {
                    String val = String.valueOf(e.getValue());
                    if (val.length() > 60) {
                        val = val.substring(0, 57) + "...";
                    }
                    return e.getKey() + "=" + val;
                })
                .collect(Collectors.joining(", "));
        if (summary.length() > MAX_ARGS_LENGTH) {
            return summary.substring(0, MAX_ARGS_LENGTH - 3) + "...";
        }
        return summary;
    }

    private static String truncateLines(String text, int maxLines) {
        String[] lines = text.split("\n");
        if (lines.length <= maxLines) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]).append("\n");
        }
        sb.append("  ... (").append(lines.length - maxLines).append(" more lines)");
        return sb.toString();
    }

    static boolean supportsColor() {
        String term = System.getenv("TERM");
        if (term == null || term.equals("dumb")) {
            return false;
        }
        // Most modern terminals support color
        return term.contains("color") || term.contains("xterm")
                || term.contains("screen") || term.contains("tmux")
                || term.contains("256") || term.contains("ansi")
                || System.getenv("COLORTERM") != null;
    }
}
