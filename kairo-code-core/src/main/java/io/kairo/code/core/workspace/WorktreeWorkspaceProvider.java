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
package io.kairo.code.core.workspace;

import io.kairo.api.workspace.Workspace;
import io.kairo.api.workspace.WorkspaceKind;
import io.kairo.api.workspace.WorkspaceProvider;
import io.kairo.api.workspace.WorkspaceRequest;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link WorkspaceProvider} that hands out per-task git worktrees so a child agent can write
 * without clobbering the parent's working tree. Backed by {@link WorktreeLifecycle}.
 *
 * <p>Resolution rules for {@link #acquire(WorkspaceRequest)}:
 *
 * <ul>
 *   <li>{@code request.hint()} blank — return the parent workspace itself (no isolation).
 *   <li>{@code request.writable() == false} — return the parent workspace (read-only sub-task,
 *       NONE-mode).
 *   <li>Parent root is not a git repo — return the parent workspace and log a warning (worktree
 *       isolation requires git).
 *   <li>Parent has uncommitted changes — refuse with {@link WorktreeException} so the caller can
 *       prompt the user to stash/commit (squash-merge would otherwise conflict on merge back).
 *   <li>Otherwise — create a new worktree, branch named {@code kairo-code/<taskId>}, and return
 *       a {@link Workspace} rooted there.
 *   <li>{@code metadata().get("kairo.workspace.isolation")} is {@code "worktree"} for isolated
 *       acquisitions and {@code "none"} for fall-through.
 * </ul>
 *
 * <p>{@link #release(String)} is a no-op for worktree workspaces — call into {@link #lifecycle()}
 * directly to merge/discard/keep based on user approval. The provider only forgets the in-memory
 * id mapping. This split keeps the SPI contract honest (release ≠ destroy) while letting the
 * {@code TaskTool} drive the user-facing approval flow.
 *
 * <p>Thread-safe.
 *
 * @since 0.1.1 (kairo-code M3)
 */
public final class WorktreeWorkspaceProvider implements WorkspaceProvider {

    private static final Logger LOG = LoggerFactory.getLogger(WorktreeWorkspaceProvider.class);

    private final Path parentRoot;
    private final WorktreeLifecycle lifecycle;
    private final boolean parentIsGitRepo;
    private final ConcurrentHashMap<String, Acquired> acquired = new ConcurrentHashMap<>();

    public WorktreeWorkspaceProvider(Path parentRoot, WorktreeLifecycle lifecycle) {
        this.parentRoot =
                Objects.requireNonNull(parentRoot, "parentRoot").toAbsolutePath().normalize();
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.parentIsGitRepo = lifecycle.isGitRepo(this.parentRoot);
    }

    public Path parentRoot() {
        return parentRoot;
    }

    public WorktreeLifecycle lifecycle() {
        return lifecycle;
    }

    public boolean parentIsGitRepo() {
        return parentIsGitRepo;
    }

    @Override
    public Workspace acquire(WorkspaceRequest request) {
        Objects.requireNonNull(request, "request");
        String hint = request.hint();
        if (hint == null || hint.isBlank()) {
            return parentWorkspace();
        }
        if (!request.writable()) {
            return parentWorkspace();
        }
        if (!parentIsGitRepo) {
            LOG.warn(
                    "Parent root {} is not a git repo; falling back to NONE isolation for task {}",
                    parentRoot,
                    hint);
            return parentWorkspace();
        }
        if (lifecycle.hasUncommittedChanges(parentRoot)) {
            throw new WorktreeException(
                    "Parent has uncommitted changes; cannot create isolated worktree for task '"
                            + hint
                            + "'. Stash or commit first, or downgrade the sub-task to read-only.");
        }
        Path wtPath = lifecycle.acquire(hint, parentRoot);
        String id = "worktree:" + WorktreeLifecycle.sanitizeTaskId(hint);
        Acquired acq = new Acquired(id, hint, wtPath);
        acquired.put(id, acq);
        return new IsolatedWorktreeWorkspace(id, wtPath, hint);
    }

    @Override
    public void release(String workspaceId) {
        if (workspaceId == null) {
            return;
        }
        // No filesystem cleanup here — the caller is expected to invoke lifecycle().merge / discard /
        // keep explicitly through the TaskTool's approval flow. We just forget the in-memory id.
        acquired.remove(workspaceId);
    }

    /** Returns the {@link Acquired} entry for a workspace id, or null. Useful for tests/diagnostics. */
    public Acquired acquired(String workspaceId) {
        return workspaceId == null ? null : acquired.get(workspaceId);
    }

    private Workspace parentWorkspace() {
        return new ParentWorkspace(parentRoot);
    }

    /** Bookkeeping record for an outstanding worktree acquisition. */
    public record Acquired(String workspaceId, String taskId, Path worktreePath) {
        public Acquired {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(worktreePath, "worktreePath");
        }
    }

    private record IsolatedWorktreeWorkspace(String id, Path root, String taskId) implements Workspace {
        @Override
        public WorkspaceKind kind() {
            return WorkspaceKind.LOCAL;
        }

        @Override
        public Map<String, String> metadata() {
            Map<String, String> m = new HashMap<>(2);
            m.put("kairo.workspace.isolation", "worktree");
            m.put("kairo.workspace.taskId", taskId);
            return Map.copyOf(m);
        }
    }

    private record ParentWorkspace(Path root) implements Workspace {
        @Override
        public String id() {
            return "worktree-parent:" + root;
        }

        @Override
        public WorkspaceKind kind() {
            return WorkspaceKind.LOCAL;
        }

        @Override
        public Map<String, String> metadata() {
            return Map.of("kairo.workspace.isolation", "none");
        }
    }
}
