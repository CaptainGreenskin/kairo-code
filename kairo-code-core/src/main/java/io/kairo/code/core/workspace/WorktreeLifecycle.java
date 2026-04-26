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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the {@code git} CLI for managing per-task worktrees used by the M3 sub-agent
 * task tool.
 *
 * <p>Each call shells out to {@code git} via {@link ProcessBuilder} with a 30 s timeout and reads
 * stdout/stderr as UTF-8. Worktrees live under {@link #baseDir} (default {@code
 * ~/.kairo-code/worktrees/<repo-fingerprint>/<task-id>/}), <strong>outside</strong> the parent
 * repository, so child writes never pollute the parent's working tree until an explicit
 * {@link #merge(String, Path)} is performed.
 *
 * <p>The class is stateless apart from {@link #baseDir} and {@link #gitCommand}; instances are
 * safe to share across threads.
 *
 * @since 0.1.1 (kairo-code M3)
 */
public final class WorktreeLifecycle {

    private static final long SUBPROCESS_TIMEOUT_SECONDS = 30L;
    private static final String BRANCH_PREFIX = "kairo-code/";

    private final Path baseDir;
    private final String gitCommand;

    /** Default — {@code ~/.kairo-code/worktrees/} + {@code git}. */
    public WorktreeLifecycle() {
        this(defaultBaseDir(), "git");
    }

    /** Explicit base directory + git executable; primarily for tests. */
    public WorktreeLifecycle(Path baseDir, String gitCommand) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir").toAbsolutePath().normalize();
        this.gitCommand = Objects.requireNonNull(gitCommand, "gitCommand");
    }

    public Path baseDir() {
        return baseDir;
    }

    /* ------------------------------------------------------------------ probes */

    /** {@code true} if {@code path} sits inside a git work tree. */
    public boolean isGitRepo(Path path) {
        Objects.requireNonNull(path, "path");
        if (!Files.isDirectory(path)) {
            return false;
        }
        try {
            ProcessResult r = run(path, List.of(gitCommand, "rev-parse", "--is-inside-work-tree"));
            return r.exitCode == 0 && r.stdout.trim().equals("true");
        } catch (WorktreeException e) {
            return false;
        }
    }

    /** {@code true} if the working tree at {@code repoRoot} has uncommitted or staged changes. */
    public boolean hasUncommittedChanges(Path repoRoot) {
        Objects.requireNonNull(repoRoot, "repoRoot");
        ProcessResult r = run(repoRoot, List.of(gitCommand, "status", "--porcelain"));
        if (r.exitCode != 0) {
            throw new WorktreeException(
                    "git status --porcelain failed (exit=" + r.exitCode + "): " + r.stderr);
        }
        return !r.stdout.isBlank();
    }

    /**
     * Stable 12-char fingerprint of a repo, derived from its {@code git} top-level path plus the
     * URL of the {@code origin} remote (if any). Used to namespace worktrees per repo so two
     * different repos don't collide on a task id.
     */
    public String fingerprint(Path repoRoot) {
        Objects.requireNonNull(repoRoot, "repoRoot");
        String topLevel = runOk(repoRoot, List.of(gitCommand, "rev-parse", "--show-toplevel")).trim();
        String origin;
        try {
            ProcessResult r = run(repoRoot, List.of(gitCommand, "remote", "get-url", "origin"));
            origin = r.exitCode == 0 ? r.stdout.trim() : "";
        } catch (WorktreeException e) {
            origin = "";
        }
        return sha256First12(topLevel + "\0" + origin);
    }

    /* ------------------------------------------------------------------ acquire */

    /**
     * Create a fresh worktree for {@code taskId}, branched from {@code parentRoot}'s current HEAD,
     * placed at {@code baseDir / fingerprint / taskId}. The branch is named {@link #BRANCH_PREFIX}
     * + sanitized task id.
     *
     * <p>Caller must verify {@link #isGitRepo(Path)} and (for write tasks) ensure {@link
     * #hasUncommittedChanges(Path)} returns {@code false} before calling — this method does not
     * second-guess the caller's intent.
     *
     * @return absolute path to the new worktree's working directory.
     * @throws WorktreeException if the target directory already exists or the {@code git worktree
     *     add} command fails.
     */
    public Path acquire(String taskId, Path parentRoot) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(parentRoot, "parentRoot");
        String safeId = sanitizeTaskId(taskId);
        String fp = fingerprint(parentRoot);
        Path target = baseDir.resolve(fp).resolve(safeId);

        if (Files.exists(target)) {
            throw new WorktreeException(
                    "Worktree path already exists; refusing to clobber: " + target);
        }
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new WorktreeException("Cannot create worktree parent dir: " + target.getParent(), e);
        }

        String branch = BRANCH_PREFIX + safeId;
        ProcessResult add =
                run(parentRoot, List.of(gitCommand, "worktree", "add", "-b", branch, target.toString()));
        if (add.exitCode != 0) {
            throw new WorktreeException(
                    "git worktree add failed (exit=" + add.exitCode + "): " + add.stderr);
        }
        // Record parent's HEAD at acquire time so diff() can compute changes regardless of how many
        // commits the child piles on. Sidecar file lives next to the worktree dir, not inside it.
        String parentHead = runOk(parentRoot, List.of(gitCommand, "rev-parse", "HEAD")).trim();
        try {
            Files.writeString(metaFile(target), parentHead + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new WorktreeException("Failed to write base-sha metadata: " + metaFile(target), e);
        }
        return target;
    }

    private static Path metaFile(Path worktreeDir) {
        return worktreeDir.resolveSibling(worktreeDir.getFileName() + ".base");
    }

    /* ------------------------------------------------------------------ inspect */

    /**
     * {@code git diff --shortstat} for the worktree against the parent's HEAD <em>at the moment the
     * worktree was acquired</em>. This captures both committed and uncommitted child changes.
     */
    public DiffStats diff(Path worktreePath) {
        Objects.requireNonNull(worktreePath, "worktreePath");
        Path meta = metaFile(worktreePath);
        if (!Files.isRegularFile(meta)) {
            throw new WorktreeException(
                    "Missing base-sha metadata at " + meta + "; was this worktree created via acquire()?");
        }
        String baseSha;
        try {
            baseSha = Files.readString(meta, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new WorktreeException("Failed to read base-sha metadata: " + meta, e);
        }
        ProcessResult r = run(worktreePath, List.of(gitCommand, "diff", baseSha, "--shortstat"));
        if (r.exitCode != 0) {
            throw new WorktreeException(
                    "git diff --shortstat failed (exit=" + r.exitCode + "): " + r.stderr);
        }
        int untracked = countUntracked(worktreePath);
        DiffStats parsed = parseShortstat(r.stdout);
        if (untracked == 0) {
            return parsed;
        }
        return new DiffStats(parsed.filesChanged() + untracked, parsed.insertions(), parsed.deletions());
    }

    private int countUntracked(Path worktreePath) {
        ProcessResult r =
                run(worktreePath, List.of(gitCommand, "ls-files", "--others", "--exclude-standard"));
        if (r.exitCode != 0) {
            return 0;
        }
        if (r.stdout.isBlank()) {
            return 0;
        }
        return r.stdout.trim().split("\\R").length;
    }

    /* ------------------------------------------------------------------ merge / discard / keep */

    /**
     * Squash-merge the worktree's branch into {@code parentRoot}'s current branch, leaving the
     * changes <strong>staged but uncommitted</strong> so the user (or REPL) can inspect and commit
     * with a custom message.
     *
     * <p>This method does not delete the worktree — call {@link #discard(String, Path)} or {@link
     * #keep(String, Path)} after merge to clean up.
     *
     * @throws WorktreeException if the parent has uncommitted changes (would conflict with the
     *     squash) or the merge command fails.
     */
    public void merge(String taskId, Path parentRoot) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(parentRoot, "parentRoot");
        if (hasUncommittedChanges(parentRoot)) {
            throw new WorktreeException(
                    "Parent has uncommitted changes; cannot squash-merge into a dirty tree. "
                            + "Stash or commit first.");
        }
        String branch = BRANCH_PREFIX + sanitizeTaskId(taskId);
        ProcessResult r = run(parentRoot, List.of(gitCommand, "merge", "--squash", branch));
        if (r.exitCode != 0) {
            throw new WorktreeException(
                    "git merge --squash " + branch + " failed (exit=" + r.exitCode + "): " + r.stderr);
        }
    }

    /**
     * Remove the worktree at {@code baseDir / fingerprint / taskId} and delete its branch. Idempotent
     * for already-removed worktrees (force-delete swallows "not a working tree" errors).
     */
    public void discard(String taskId, Path parentRoot) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(parentRoot, "parentRoot");
        String safeId = sanitizeTaskId(taskId);
        Path target = baseDir.resolve(fingerprint(parentRoot)).resolve(safeId);
        String branch = BRANCH_PREFIX + safeId;

        if (Files.exists(target)) {
            ProcessResult rm =
                    run(parentRoot, List.of(gitCommand, "worktree", "remove", "--force", target.toString()));
            if (rm.exitCode != 0) {
                throw new WorktreeException(
                        "git worktree remove failed (exit=" + rm.exitCode + "): " + rm.stderr);
            }
        }
        try {
            Files.deleteIfExists(metaFile(target));
        } catch (IOException ignored) {
            // best-effort cleanup
        }
        // Branch may already be gone if the worktree dir was deleted manually.
        ProcessResult br = run(parentRoot, List.of(gitCommand, "branch", "-D", branch));
        if (br.exitCode != 0 && !br.stderr.contains("not found")) {
            throw new WorktreeException(
                    "git branch -D " + branch + " failed (exit=" + br.exitCode + "): " + br.stderr);
        }
    }

    /**
     * Mark the worktree as kept (no-op on filesystem; returns the path so the caller can show it to
     * the user). The worktree and branch remain on disk; clean up via {@code git worktree remove}
     * later if needed.
     */
    public Path keep(String taskId, Path parentRoot) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(parentRoot, "parentRoot");
        return baseDir.resolve(fingerprint(parentRoot)).resolve(sanitizeTaskId(taskId));
    }

    /* ------------------------------------------------------------------ helpers */

    private static Path defaultBaseDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new WorktreeException("user.home system property is unset");
        }
        return Path.of(home, ".kairo-code", "worktrees");
    }

    static String sanitizeTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        String s = taskId.trim().replaceAll("[^A-Za-z0-9_-]+", "-");
        // Collapse repeated dashes and trim ends so we don't end up with `kairo-code/-foo-`.
        s = s.replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        if (s.isBlank()) {
            throw new IllegalArgumentException("taskId reduced to empty after sanitisation: " + taskId);
        }
        return s;
    }

    static DiffStats parseShortstat(String shortstat) {
        if (shortstat == null || shortstat.isBlank()) {
            return DiffStats.EMPTY;
        }
        // Example: ` 3 files changed, 12 insertions(+), 4 deletions(-)`
        int files = extractIntBefore(shortstat, "file");
        int ins = extractIntBefore(shortstat, "insertion");
        int del = extractIntBefore(shortstat, "deletion");
        return new DiffStats(files, ins, del);
    }

    private static int extractIntBefore(String text, String token) {
        int idx = text.indexOf(token);
        if (idx < 0) {
            return 0;
        }
        int end = idx;
        // Walk backwards over whitespace then digits.
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && Character.isDigit(text.charAt(start - 1))) {
            start--;
        }
        if (start == end) {
            return 0;
        }
        try {
            return Integer.parseInt(text.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String sha256First12(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new WorktreeException("SHA-256 unavailable on this JVM", e);
        }
    }

    /* ------------------------------------------------------------------ subprocess */

    private String runOk(Path cwd, List<String> command) {
        ProcessResult r = run(cwd, command);
        if (r.exitCode != 0) {
            throw new WorktreeException(
                    String.join(" ", command) + " failed (exit=" + r.exitCode + "): " + r.stderr);
        }
        return r.stdout;
    }

    private ProcessResult run(Path cwd, List<String> command) {
        ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(false);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new WorktreeException("Failed to launch: " + String.join(" ", command), e);
        }
        try {
            byte[] outBytes = p.getInputStream().readAllBytes();
            byte[] errBytes = p.getErrorStream().readAllBytes();
            if (!p.waitFor(SUBPROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new WorktreeException(
                        "Subprocess timed out after "
                                + SUBPROCESS_TIMEOUT_SECONDS
                                + "s: "
                                + String.join(" ", command));
            }
            return new ProcessResult(
                    p.exitValue(),
                    new String(outBytes, StandardCharsets.UTF_8),
                    new String(errBytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            p.destroyForcibly();
            throw new WorktreeException("I/O error reading subprocess output", e);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new WorktreeException("Interrupted waiting for: " + String.join(" ", command), e);
        }
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
