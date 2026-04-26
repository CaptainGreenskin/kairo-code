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

import io.kairo.code.core.workspace.WorktreeWorkspaceProvider;
import java.util.Objects;

/**
 * Dependency bundle for {@link TaskTool}, registered in an agent's {@code toolDependencies} map
 * and retrieved at execution time via {@link io.kairo.api.tool.ToolContext#getBean(Class)}.
 *
 * <p>Why a single bundle instead of three independent beans: the three components are always
 * configured together (acquire a workspace → spawn a child in it → prompt the user about the
 * worktree) and decoupling them risks half-wired sessions where the worktree provider is present
 * but no spawner is — a runtime failure mode that's easy to avoid by requiring all three.
 *
 * @param workspaceProvider used to acquire/release per-task worktrees
 * @param spawner builds the child {@code CodeAgentSession}; never null
 * @param mergePrompter asks the user merge/discard/keep after the child finishes; never null
 */
public record TaskToolDependencies(
        WorktreeWorkspaceProvider workspaceProvider,
        ChildSessionSpawner spawner,
        WorktreeMergePrompter mergePrompter) {

    public TaskToolDependencies {
        Objects.requireNonNull(workspaceProvider, "workspaceProvider");
        Objects.requireNonNull(spawner, "spawner");
        Objects.requireNonNull(mergePrompter, "mergePrompter");
    }
}
