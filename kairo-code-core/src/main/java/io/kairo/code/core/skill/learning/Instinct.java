package io.kairo.code.core.skill.learning;

/**
 * A learned pattern extracted from tool observations.
 *
 * @param id        deterministic ID from trigger + action
 * @param trigger   what causes this pattern (e.g., "test failure")
 * @param action    what the agent should do (e.g., "read error, fix, re-run")
 * @param domain    category (workflow, testing, debugging, code-style, git)
 * @param confidence 0.0-1.0, increases with more evidence
 * @param evidence  number of times this pattern was observed
 */
public record Instinct(
        String id,
        String trigger,
        String action,
        String domain,
        double confidence,
        int evidence) {

    public Instinct withMoreEvidence() {
        double newConfidence = Math.min(1.0, confidence + 0.1);
        return new Instinct(id, trigger, action, domain, newConfidence, evidence + 1);
    }

    public static String computeId(String trigger, String action) {
        int hash = (trigger + "::" + action).hashCode();
        return "instinct-" + Integer.toHexString(hash & 0x7fffffff);
    }
}
