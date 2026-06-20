package io.kairo.code.core.skill.learning;

import java.time.Instant;

/**
 * A single observed tool invocation during a session.
 */
public record ToolObservation(
        String toolName,
        String inputSummary,
        boolean success,
        String errorMessage,
        Instant timestamp) {

    public static ToolObservation success(String toolName, String inputSummary) {
        return new ToolObservation(toolName, inputSummary, true, null, Instant.now());
    }

    public static ToolObservation failure(String toolName, String inputSummary, String error) {
        return new ToolObservation(toolName, inputSummary, false, error, Instant.now());
    }
}
