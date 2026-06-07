package io.kairo.code.core;

import io.kairo.code.core.team.SharedTaskList;

/**
 * Process-wide singleton {@link SharedTaskList} shared across all agent sessions.
 * Ensures coordinator and spawned workers (which are separate CodeAgentSession instances)
 * see the same task board.
 */
public final class GlobalSharedTaskList {
    public static final SharedTaskList INSTANCE = new SharedTaskList("global");
    private GlobalSharedTaskList() {}
}
