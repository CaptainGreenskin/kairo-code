package io.kairo.code.core.evolution;

import java.util.List;

/**
 * Event emitted when a tool reaches the strike-3 consecutive failure threshold.
 *
 * @param toolName      the name of the failing tool
 * @param recentErrors  the last 3 error result contents leading up to the strike
 */
public record ToolStrikeEvent(String toolName, List<String> recentErrors) {
}
