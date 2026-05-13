package io.kairo.code.service.team;

/**
 * Determines whether a user goal warrants full expert-team fan-out
 * or can be served with a simpler single-agent ReAct loop.
 */
public interface TriageGate {
    /**
     * Returns {@code true} if the goal is complex enough to warrant
     * multi-expert team collaboration (fan-out); {@code false} to
     * demote to single-agent ReAct.
     */
    boolean shouldFanOut(String goal);
}
