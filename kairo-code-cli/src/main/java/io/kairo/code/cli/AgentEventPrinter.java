package io.kairo.code.cli;

import io.kairo.api.hook.HookHandler;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.PostActingEvent;
import io.kairo.api.hook.PostReasoningEvent;
import io.kairo.api.hook.PreActingEvent;
import io.kairo.api.message.Content;
import io.kairo.api.model.ModelResponse;
import java.io.PrintWriter;
import java.util.Map;
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

    public AgentEventPrinter(PrintWriter writer) {
        this(writer, "", false);
    }

    /**
     * Prefix-aware variant for child agents spawned via the {@code task} tool. Pass e.g. {@code
     * "[task:t-abc12345] "} so streaming output from the child is visually distinct from the
     * parent's. Pass an empty string for the parent.
     */
    public AgentEventPrinter(PrintWriter writer, String prefix) {
        this(writer, prefix, false);
    }

    /**
     * Full constructor. When {@code streamingText} is true, per-token text output is handled by a
     * separate consumer; {@link #onPostReasoning} skips printing the text body to avoid duplication.
     */
    public AgentEventPrinter(PrintWriter writer, String prefix, boolean streamingText) {
        this.writer = writer;
        this.prefix = prefix == null ? "" : prefix;
        this.streamingText = streamingText;
    }

    private String linePrefix() {
        return prefix.isEmpty() ? "" : DIM + prefix + RESET;
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
        if (event.response() == null || event.response().contents() == null) {
            return;
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
            writer.printf(linePrefix() + DIM + "[model] in=%d out=%d cache_read=%d" + RESET + "%n",
                    usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens());
        }
        writer.flush();
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

    private static boolean supportsColor() {
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
