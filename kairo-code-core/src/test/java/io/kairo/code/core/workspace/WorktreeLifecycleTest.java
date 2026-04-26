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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreeLifecycleTest {

    private static final long PROC_TIMEOUT_SECONDS = 30;

    @BeforeAll
    static void requireGit() {
        assumeTrue(commandAvailable("git"), "git CLI not available; skipping worktree e2e tests");
    }

    @Nested
    class Pure {

        @Test
        void sanitizeTaskIdReplacesUnsafeChars() {
            assertThat(WorktreeLifecycle.sanitizeTaskId("abc 123")).isEqualTo("abc-123");
            assertThat(WorktreeLifecycle.sanitizeTaskId("a/b\\c:d")).isEqualTo("a-b-c-d");
            assertThat(WorktreeLifecycle.sanitizeTaskId("--leading--trailing--"))
                    .isEqualTo("leading-trailing");
        }

        @Test
        void sanitizeTaskIdRejectsBlank() {
            assertThatThrownBy(() -> WorktreeLifecycle.sanitizeTaskId(""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> WorktreeLifecycle.sanitizeTaskId("///"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void parseShortstatHappyPath() {
            DiffStats s =
                    WorktreeLifecycle.parseShortstat(
                            " 3 files changed, 12 insertions(+), 4 deletions(-)");
            assertThat(s).isEqualTo(new DiffStats(3, 12, 4));
        }

        @Test
        void parseShortstatInsertionsOnly() {
            DiffStats s = WorktreeLifecycle.parseShortstat(" 1 file changed, 7 insertions(+)");
            assertThat(s).isEqualTo(new DiffStats(1, 7, 0));
        }

        @Test
        void parseShortstatBlankReturnsEmpty() {
            assertThat(WorktreeLifecycle.parseShortstat("")).isSameAs(DiffStats.EMPTY);
            assertThat(WorktreeLifecycle.parseShortstat(null)).isSameAs(DiffStats.EMPTY);
        }
    }

    @Nested
    class GitE2E {

        @Test
        void acquireCreatesWorktreeAndDiscardCleansUp(@TempDir Path tmp) throws Exception {
            Path repo = initRepoWith(tmp.resolve("parent"), "README.md", "hello\n");
            WorktreeLifecycle wtl = new WorktreeLifecycle(tmp.resolve("worktrees"), "git");

            assertThat(wtl.isGitRepo(repo)).isTrue();
            assertThat(wtl.hasUncommittedChanges(repo)).isFalse();

            String taskId = "T-001";
            Path wt = wtl.acquire(taskId, repo);
            assertThat(wt).exists().isDirectory();
            assertThat(wt.resolve("README.md")).exists();
            assertThat(wt).startsWith(tmp.resolve("worktrees"));
            assertThat(wt).isNotEqualTo(repo);

            wtl.discard(taskId, repo);
            assertThat(wt).doesNotExist();
        }

        @Test
        void acquireWritesStayInWorktreeUntilMerge(@TempDir Path tmp) throws Exception {
            Path repo = initRepoWith(tmp.resolve("parent"), "README.md", "hello\n");
            WorktreeLifecycle wtl = new WorktreeLifecycle(tmp.resolve("worktrees"), "git");

            String taskId = "feat-add-greeting";
            Path wt = wtl.acquire(taskId, repo);

            // Child writes a file inside the worktree.
            Files.writeString(wt.resolve("greeting.txt"), "hi from child\n", StandardCharsets.UTF_8);
            // Stage + commit so merge --squash has something to take.
            runOk(wt, List.of("git", "add", "greeting.txt"));
            runOk(wt, List.of("git", "-c", "user.email=t@t", "-c", "user.name=T", "commit", "-m", "child"));

            // Parent's working dir is clean of the new file.
            assertThat(repo.resolve("greeting.txt")).doesNotExist();

            DiffStats stats = wtl.diff(wt);
            assertThat(stats.filesChanged()).isGreaterThanOrEqualTo(1);
            assertThat(stats.insertions()).isGreaterThanOrEqualTo(1);

            // Merge brings change into parent as staged.
            wtl.merge(taskId, repo);
            assertThat(repo.resolve("greeting.txt")).exists();

            // Now parent has a staged change; clean it up before discard so post-merge state is sane.
            runOk(repo, List.of("git", "reset", "--hard", "HEAD"));
            wtl.discard(taskId, repo);
            assertThat(wt).doesNotExist();
        }

        @Test
        void mergeRefusesIfParentDirty(@TempDir Path tmp) throws Exception {
            Path repo = initRepoWith(tmp.resolve("parent"), "README.md", "hello\n");
            WorktreeLifecycle wtl = new WorktreeLifecycle(tmp.resolve("worktrees"), "git");
            Path wt = wtl.acquire("T", repo);
            Files.writeString(wt.resolve("a.txt"), "a\n");
            runOk(wt, List.of("git", "add", "a.txt"));
            runOk(
                    wt,
                    List.of("git", "-c", "user.email=t@t", "-c", "user.name=T", "commit", "-m", "child"));

            // Dirty the parent.
            Files.writeString(repo.resolve("dirty.txt"), "x\n");

            assertThatThrownBy(() -> wtl.merge("T", repo))
                    .isInstanceOf(WorktreeException.class)
                    .hasMessageContaining("uncommitted");
        }

        @Test
        void fingerprintIsStable(@TempDir Path tmp) throws Exception {
            Path repo = initRepoWith(tmp.resolve("parent"), "README.md", "hello\n");
            WorktreeLifecycle wtl = new WorktreeLifecycle(tmp.resolve("worktrees"), "git");

            String fp1 = wtl.fingerprint(repo);
            String fp2 = wtl.fingerprint(repo);
            assertThat(fp1).isEqualTo(fp2).hasSize(12).matches("[0-9a-f]{12}");
        }

        @Test
        void isGitRepoFalseForBareDir(@TempDir Path tmp) {
            WorktreeLifecycle wtl = new WorktreeLifecycle(tmp.resolve("worktrees"), "git");
            assertThat(wtl.isGitRepo(tmp)).isFalse();
        }

        @Test
        void acquireRefusesIfTargetExists(@TempDir Path tmp) throws Exception {
            Path repo = initRepoWith(tmp.resolve("parent"), "README.md", "hello\n");
            WorktreeLifecycle wtl = new WorktreeLifecycle(tmp.resolve("worktrees"), "git");
            wtl.acquire("dup", repo);
            assertThatThrownBy(() -> wtl.acquire("dup", repo))
                    .isInstanceOf(WorktreeException.class)
                    .hasMessageContaining("already exists");
        }
    }

    /* ------------------------------------------------------------------ helpers */

    private static Path initRepoWith(Path repoDir, String firstFile, String content) throws Exception {
        Files.createDirectories(repoDir);
        runOk(repoDir, List.of("git", "init", "-q", "-b", "main"));
        runOk(repoDir, List.of("git", "config", "user.email", "t@t"));
        runOk(repoDir, List.of("git", "config", "user.name", "Test"));
        Files.writeString(repoDir.resolve(firstFile), content, StandardCharsets.UTF_8);
        runOk(repoDir, List.of("git", "add", firstFile));
        runOk(repoDir, List.of("git", "commit", "-q", "-m", "init"));
        return repoDir;
    }

    private static void runOk(Path cwd, List<String> command) throws Exception {
        Process p =
                new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(PROC_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
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
            return p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
