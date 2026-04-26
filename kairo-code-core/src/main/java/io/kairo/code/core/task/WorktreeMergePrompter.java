/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kairo.code.core.task;

import io.kairo.code.core.workspace.DiffStats;
import java.nio.file.Path;
import reactor.core.publisher.Mono;

/**
 * Asks the user what to do with a child task's worktree after the child agent finishes (merge,
 * discard, or keep on disk). The REPL ships a console-reading default; tests inject a fixed
 * choice.
 */
@FunctionalInterface
public interface WorktreeMergePrompter {

    /**
     * Prompt the user. Implementations may block waiting on stdin/UI input. Returning {@link
     * WorktreeMergeChoice#DISCARD} is the safest default if the implementation can't decide (e.g.
     * timeout, EOF).
     *
     * @param taskId the child task id
     * @param description short human-readable task description (one line)
     * @param stats diff stats vs the parent's HEAD at acquire time
     * @param worktreePath absolute path to the worktree (so the prompter can show it to the user)
     */
    Mono<WorktreeMergeChoice> prompt(
            String taskId, String description, DiffStats stats, Path worktreePath);
}
