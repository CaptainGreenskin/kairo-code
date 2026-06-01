package io.kairo.code.service.agent;

import io.kairo.code.core.task.WorktreeMergeChoice;
import io.kairo.code.core.task.WorktreeMergePrompter;
import io.kairo.code.core.workspace.DiffStats;
import java.nio.file.Path;
import reactor.core.publisher.Mono;

/**
 * Server-side merge prompter that auto-merges child task worktrees. In the web UI
 * there is no terminal to prompt the user, and bypass-mode sessions should not block
 * on interactive input. The policy is: merge if the child made changes, discard if
 * the diff is empty.
 */
public final class AutoMergePrompter implements WorktreeMergePrompter {

    @Override
    public Mono<WorktreeMergeChoice> prompt(
            String taskId, String description, DiffStats stats, Path worktreePath) {
        if (stats != null && !stats.isEmpty()) {
            return Mono.just(WorktreeMergeChoice.MERGE);
        }
        return Mono.just(WorktreeMergeChoice.DISCARD);
    }
}
