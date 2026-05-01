package io.kairo.code.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses plan steps from agent text output.
 * Detects patterns like:
 *   - "- [ ] Step description"
 *   - "1. Step description"
 *   - "## Steps\n1. ..."
 */
public class PlanStepParser {

    // Matches "- [ ] ..." or "- [x] ..."
    private static final Pattern CHECKBOX = Pattern.compile(
            "^\\s*-\\s*\\[([ xX])\\]\\s*(.+)", Pattern.MULTILINE);

    // Matches "1. ..." numbered list
    private static final Pattern NUMBERED = Pattern.compile(
            "^\\s*(\\d+)\\.\\s+(.+)", Pattern.MULTILINE);

    private static final int MIN_STEPS = 2;   // need at least 2 steps to be "a plan"
    private static final int MAX_STEPS = 20;

    private PlanStepParser() {
    }

    /**
     * Returns extracted steps if text looks like a plan, empty list otherwise.
     */
    public static List<String> parse(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<String> steps = new ArrayList<>();

        // Try checkbox format first
        Matcher m = CHECKBOX.matcher(text);
        while (m.find() && steps.size() < MAX_STEPS) {
            steps.add(m.group(2).trim());
        }

        // Fall back to numbered list
        if (steps.size() < MIN_STEPS) {
            steps.clear();
            Matcher nm = NUMBERED.matcher(text);
            while (nm.find() && steps.size() < MAX_STEPS) {
                steps.add(nm.group(2).trim());
            }
        }

        return steps.size() >= MIN_STEPS ? List.copyOf(steps) : List.of();
    }

    /**
     * Returns true if text appears to complete a previously-mentioned step.
     * Heuristic: mentions "done", "completed", or checkmark emojis near step content.
     */
    public static boolean looksLikeDone(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("done") || lower.contains("completed") ||
               lower.contains("✓") || lower.contains("✅") ||
               lower.contains("finished") || lower.contains("complete");
    }
}
