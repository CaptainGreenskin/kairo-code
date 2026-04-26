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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.kairo.api.workspace.Workspace;
import io.kairo.api.workspace.WorkspaceKind;
import io.kairo.api.workspace.WorkspaceRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreeWorkspaceProviderTest {

    @BeforeAll
    static void requireGit() {
        assumeTrue(commandAvailable("git"), "git CLI not available; skipping");
    }

    @Test
    void blankHintReturnsParentWorkspace(@TempDir Path tmp) throws Exception {
        Path repo = initRepo(tmp.resolve("parent"));
        WorktreeWorkspaceProvider p = newProvider(repo, tmp);
        Workspace ws = p.acquire(WorkspaceRequest.writable(""));
        assertThat(ws.root()).isEqualTo(repo);
        assertThat(ws.metadata()).containsEntry("kairo.workspace.isolation", "none");
    }

    @Test
    void readOnlyHintReturnsParentWorkspace(@TempDir Path tmp) throws Exception {
        Path repo = initRepo(tmp.resolve("parent"));
        WorktreeWorkspaceProvider p = newProvider(repo, tmp);
        Workspace ws = p.acquire(WorkspaceRequest.readOnly("read-task"));
        assertThat(ws.root()).isEqualTo(repo);
        assertThat(ws.metadata()).containsEntry("kairo.workspace.isolation", "none");
    }

    @Test
    void nonGitParentDegradesToNoneWithWarning(@TempDir Path tmp) {
        Path notRepo = tmp.resolve("not-a-repo");
        try {
            Files.createDirectories(notRepo);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        WorktreeWorkspaceProvider p = newProvider(notRepo, tmp);
        Workspace ws = p.acquire(WorkspaceRequest.writable("any-task"));
        assertThat(ws.root()).isEqualTo(notRepo);
        assertThat(ws.metadata()).containsEntry("kairo.workspace.isolation", "none");
    }

    @Test
    void writableHintCreatesWorktreeWorkspace(@TempDir Path tmp) throws Exception {
        Path repo = initRepo(tmp.resolve("parent"));
        WorktreeWorkspaceProvider p = newProvider(repo, tmp);
        Workspace ws = p.acquire(WorkspaceRequest.writable("feat-x"));

        assertThat(ws.kind()).isEqualTo(WorkspaceKind.LOCAL);
        assertThat(ws.metadata()).containsEntry("kairo.workspace.isolation", "worktree");
        assertThat(ws.metadata()).containsEntry("kairo.workspace.taskId", "feat-x");
        assertThat(ws.id()).isEqualTo("worktree:feat-x");
        assertThat(ws.root()).isNotEqualTo(repo).exists();

        WorktreeWorkspaceProvider.Acquired acq = p.acquired(ws.id());
        assertThat(acq).isNotNull();
        assertThat(acq.taskId()).isEqualTo("feat-x");
        assertThat(acq.worktreePath()).isEqualTo(ws.root());
    }

    @Test
    void dirtyParentRefusesAcquire(@TempDir Path tmp) throws Exception {
        Path repo = initRepo(tmp.resolve("parent"));
        Files.writeString(repo.resolve("dirty.txt"), "x\n");
        WorktreeWorkspaceProvider p = newProvider(repo, tmp);
        assertThatThrownBy(() -> p.acquire(WorkspaceRequest.writable("feat-y")))
                .isInstanceOf(WorktreeException.class)
                .hasMessageContaining("uncommitted");
    }

    @Test
    void releaseRemovesIdMappingButLeavesDisk(@TempDir Path tmp) throws Exception {
        Path repo = initRepo(tmp.resolve("parent"));
        WorktreeWorkspaceProvider p = newProvider(repo, tmp);
        Workspace ws = p.acquire(WorkspaceRequest.writable("feat-z"));
        Path wt = ws.root();
        assertThat(p.acquired(ws.id())).isNotNull();

        p.release(ws.id());

        assertThat(p.acquired(ws.id())).isNull();
        assertThat(wt).exists();
    }

    @Test
    void releaseUnknownIdIsNoOp(@TempDir Path tmp) throws Exception {
        Path repo = initRepo(tmp.resolve("parent"));
        WorktreeWorkspaceProvider p = newProvider(repo, tmp);
        p.release("worktree:never-acquired");
        p.release(null);
    }

    /* ------------------------------------------------------------------ helpers */

    private static WorktreeWorkspaceProvider newProvider(Path parentRoot, Path tmp) {
        WorktreeLifecycle lc = new WorktreeLifecycle(tmp.resolve("worktrees"), "git");
        return new WorktreeWorkspaceProvider(parentRoot, lc);
    }

    private static Path initRepo(Path repoDir) throws Exception {
        Files.createDirectories(repoDir);
        runOk(repoDir, List.of("git", "init", "-q", "-b", "main"));
        runOk(repoDir, List.of("git", "config", "user.email", "t@t"));
        runOk(repoDir, List.of("git", "config", "user.name", "Test"));
        Files.writeString(repoDir.resolve("README.md"), "hello\n", StandardCharsets.UTF_8);
        runOk(repoDir, List.of("git", "add", "README.md"));
        runOk(repoDir, List.of("git", "commit", "-q", "-m", "init"));
        return repoDir;
    }

    private static void runOk(Path cwd, List<String> command) throws Exception {
        Process p =
                new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("Timeout: " + command);
        }
        if (p.exitValue() != 0) {
            throw new IOException(
                    "Exit " + p.exitValue() + " for " + command + ": " + new String(out, StandardCharsets.UTF_8));
        }
    }

    private static boolean commandAvailable(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
