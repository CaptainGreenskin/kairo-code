package io.kairo.code.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accumulates multi-line input for the REPL.
 *
 * <p>Supports two multi-line modes:
 * <ul>
 *   <li><b>Continuation</b> — a line ending with {@code \} is joined with the next line(s).</li>
 *   <li><b>Heredoc</b> — a line matching {@code <<WORD} starts a heredoc block; subsequent lines
 *       are accumulated until a line containing only {@code WORD} is encountered.</li>
 * </ul>
 *
 * <p>Usage: call {@link #feed(String)} for each line read from the terminal. When the input is
 * complete, it returns the assembled string; otherwise it returns empty (still accumulating).
 */
public class InputAccumulator {

    /** The current accumulation mode. */
    public enum Mode { NORMAL, CONTINUATION, HEREDOC }

    private static final Pattern HEREDOC_PATTERN = Pattern.compile("^<<([A-Za-z_][A-Za-z0-9_]*)$");

    private Mode mode = Mode.NORMAL;
    private String heredocDelimiter;
    private final List<String> lines = new ArrayList<>();

    /**
     * Feed a line from the REPL.
     *
     * @param line the raw line (without trailing newline)
     * @return the complete input when ready, or empty if still accumulating
     */
    public Optional<String> feed(String line) {
        return switch (mode) {
            case NORMAL -> feedNormal(line);
            case CONTINUATION -> feedContinuation(line);
            case HEREDOC -> feedHeredoc(line);
        };
    }

    private Optional<String> feedNormal(String line) {
        // Check for heredoc start: <<WORD (entire line)
        Matcher m = HEREDOC_PATTERN.matcher(line.trim());
        if (m.matches()) {
            heredocDelimiter = m.group(1);
            mode = Mode.HEREDOC;
            lines.clear();
            return Optional.empty();
        }

        // Check for continuation: line ends with backslash
        if (line.endsWith("\\")) {
            mode = Mode.CONTINUATION;
            lines.clear();
            lines.add(line.substring(0, line.length() - 1));
            return Optional.empty();
        }

        // Normal single-line input
        return Optional.of(line);
    }

    private Optional<String> feedContinuation(String line) {
        if (line.endsWith("\\")) {
            // Still continuing — strip trailing backslash and accumulate
            lines.add(line.substring(0, line.length() - 1));
            return Optional.empty();
        }
        // Final line — add and join
        lines.add(line);
        String result = String.join("\n", lines);
        reset();
        return Optional.of(result);
    }

    private Optional<String> feedHeredoc(String line) {
        if (line.trim().equals(heredocDelimiter)) {
            // Delimiter found — join accumulated lines and return
            String result = String.join("\n", lines);
            reset();
            return Optional.of(result);
        }
        // Accumulate content line (backslashes are NOT special inside heredocs)
        lines.add(line);
        return Optional.empty();
    }

    /** Reset the accumulator to NORMAL mode, clearing all buffered state. */
    public void reset() {
        mode = Mode.NORMAL;
        heredocDelimiter = null;
        lines.clear();
    }

    /** Return the current accumulation mode. */
    public Mode getMode() {
        return mode;
    }

    /** Return {@code true} if the accumulator is in the middle of a multi-line input. */
    public boolean isAccumulating() {
        return mode != Mode.NORMAL;
    }
}
