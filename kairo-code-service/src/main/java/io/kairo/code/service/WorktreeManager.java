package io.kairo.code.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Per-session git worktree lifecycle.
 *
 * <p>Workflow:
 * <ul>
 *   <li>{@link #acquire(String, String, boolean)} — if {@code useWorktree=true} and the workspace
 *       is a git repo, create a worktree under {@code <workspace>/.kairo-code/worktrees/<sid>}
 *       on a fresh branch {@code kairo/<sid8>}; return the worktree path. Otherwise, return the
 *       workspace dir unchanged (with a warning if useWorktree was requested but git is missing).
 *   <li>{@link #release(String)} — best-effort cleanup. If worktree dir is clean (no uncommitted
 *       changes), {@code git worktree remove} + branch delete. If dirty, leave it on disk and log
 *       so the user can recover their work; never throw.
 * </ul>
 */
@Component
public class WorktreeManager {

    private static final Logger log = LoggerFactory.getLogger(WorktreeManager.class);

    /** sessionId → bookkeeping for sessions that actually got a worktree. */
    private final Map<String, Entry> active = new ConcurrentHashMap<>();

    /**
     * Resolve the working directory for a new session.
     *
     * @param sessionId    new session ID (used to derive worktree path + branch)
     * @param workspaceDir workspace's configured workingDir
     * @param useWorktree  workspace's useWorktree flag
     * @return the directory the session should run in (worktree path or workspaceDir)
     */
    public String acquire(String sessionId, String workspaceDir, boolean useWorktree) {
        if (!useWorktree) {
            return workspaceDir;
        }
        if (!isGitRepo(workspaceDir)) {
            log.warn("Workspace {} is not a git repo — falling back to workspace dir for session {}",
                    workspaceDir, sessionId);
            return workspaceDir;
        }
        String shortSid = sessionId.length() >= 8 ? sessionId.substring(0, 8) : sessionId;
        String branch = "kairo/" + shortSid;
        Path worktreePath = Path.of(workspaceDir, ".kairo-code", "worktrees", sessionId);
        try {
            Files.createDirectories(worktreePath.getParent());
            int rc = run(workspaceDir, "git", "worktree", "add", worktreePath.toString(), "-b", branch);
            if (rc != 0) {
                log.warn("git worktree add failed (rc={}) for session {} — falling back to workspace dir",
                        rc, sessionId);
                return workspaceDir;
            }
            active.put(sessionId, new Entry(workspaceDir, worktreePath.toString(), branch));
            log.info("Created worktree for session {} at {} (branch={})",
                    sessionId, worktreePath, branch);
            return worktreePath.toString();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Failed to create worktree for session {}: {}", sessionId, e.getMessage());
            return workspaceDir;
        }
    }

    /**
     * Release the worktree for a session. Removes if clean, retains if dirty.
     */
    public void release(String sessionId) {
        Entry entry = active.remove(sessionId);
        if (entry == null) {
            return;
        }
        boolean dirty;
        try {
            dirty = isDirty(entry.worktreePath());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Failed to check worktree status for session {} — retaining: {}",
                    sessionId, e.getMessage());
            return;
        }
        if (dirty) {
            log.info("Worktree for session {} has uncommitted changes — retaining at {}",
                    sessionId, entry.worktreePath());
            return;
        }
        try {
            int rc = run(entry.workspaceDir(), "git", "worktree", "remove", entry.worktreePath());
            if (rc != 0) {
                log.warn("git worktree remove failed (rc={}) for session {} — leaving on disk",
                        rc, sessionId);
                return;
            }
            // Also delete the branch (best-effort; unborn branch deletion may fail if no commits).
            int br = run(entry.workspaceDir(), "git", "branch", "-D", entry.branch());
            if (br != 0) {
                log.debug("git branch -D {} returned {} for session {}", entry.branch(), br, sessionId);
            }
            log.info("Removed clean worktree for session {} at {}", sessionId, entry.worktreePath());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Failed to remove worktree for session {}: {}", sessionId, e.getMessage());
        }
    }

    private boolean isGitRepo(String dir) {
        try {
            return run(dir, "git", "rev-parse", "--is-inside-work-tree") == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private boolean isDirty(String dir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "status", "--porcelain")
                .directory(Path.of(dir).toFile())
                .redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line);
                if (!line.isBlank()) {
                    p.waitFor(10, TimeUnit.SECONDS);
                    return true;
                }
            }
        }
        p.waitFor(10, TimeUnit.SECONDS);
        return !out.toString().isBlank();
    }

    private int run(String cwd, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(Path.of(cwd).toFile())
                .redirectErrorStream(true);
        Process p = pb.start();
        // Drain to avoid pipe-full deadlocks; do not log on success.
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("git command timed out: " + String.join(" ", cmd));
            }
            int rc = p.exitValue();
            if (rc != 0) {
                log.debug("`{}` (cwd={}) rc={} output={}", String.join(" ", cmd), cwd, rc, sb);
            }
            return rc;
        }
    }

    private record Entry(String workspaceDir, String worktreePath, String branch) {}
}
