package io.kairo.code.service.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Git stash-based workspace snapshot service.
 *
 * <p>Provides the ability to snapshot the current workspace state before potentially
 * destructive operations (e.g. builds, refactors) and revert if needed.
 *
 * <p><strong>Important:</strong> Snapshots do NOT include gitignored files (e.g.
 * {@code node_modules/}, {@code target/}, {@code .idea/}). Only tracked and untracked
 * (but not ignored) files are captured via {@code git stash -u}.
 */
@Service
public class WorkspaceSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSnapshotService.class);

    /** Sentinel value returned when the worktree is already clean (nothing to snapshot). */
    private static final String CLEAN_SENTINEL = "CLEAN";

    private static final String SESSION_DIR = ".kairo-session";
    private static final String SNAPSHOT_REF_FILE = "snapshot.ref";

    /**
     * Create a snapshot of the current workspace state using git stash.
     *
     * <p>If the worktree is dirty, a stash entry is created and stored with a descriptive
     * message. If the worktree is clean, the {@code "CLEAN"} sentinel is returned.
     *
     * <p>The snapshot ref is persisted to {@code {workspaceDir}/.kairo-session/snapshot.ref}
     * for later recovery.
     *
     * @param workspaceDir absolute path to the workspace root (must be a git repository)
     * @return the stash ref (SHA), or {@code "CLEAN"} if the worktree was already clean;
     *         empty if the snapshot operation failed
     */
    public Optional<String> createSnapshot(String workspaceDir) {
        try {
            // git stash create -u: creates a stash commit without modifying the working tree
            String ref = runAndCapture(workspaceDir, "git", "stash", "create", "-u");

            if (ref.isBlank()) {
                // Clean worktree — nothing to stash
                log.info("Workspace {} is clean — no snapshot needed", workspaceDir);
                persistRef(workspaceDir, CLEAN_SENTINEL);
                return Optional.of(CLEAN_SENTINEL);
            }

            // Store the stash ref with a descriptive message
            String message = "kairo: snapshot before build " + Instant.now().toString();
            int rc = run(workspaceDir, "git", "stash", "store", "-m", message, ref);
            if (rc != 0) {
                log.error("git stash store failed (rc={}) for workspace {}", rc, workspaceDir);
                return Optional.empty();
            }

            persistRef(workspaceDir, ref);
            log.info("Created snapshot {} for workspace {}", ref, workspaceDir);
            return Optional.of(ref);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Failed to create snapshot for workspace {}: {}", workspaceDir, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Revert the workspace to the state captured by the given snapshot ref.
     *
     * <p>For a {@code "CLEAN"} ref, this simply resets the worktree. For a real stash ref,
     * the stash is applied after resetting.
     *
     * @param workspaceDir absolute path to the workspace root
     * @param snapshotRef  the ref returned by {@link #createSnapshot(String)}
     * @return {@code true} if the revert succeeded
     */
    public boolean revert(String workspaceDir, String snapshotRef) {
        try {
            // Revert tracked file changes (untracked removed by subsequent git clean)
            int resetRc = run(workspaceDir, "git", "reset", "--hard", "HEAD");
            if (resetRc != 0) {
                log.error("git reset --hard failed (rc={}) for workspace {}", resetRc, workspaceDir);
                return false;
            }

            // git clean -fd: remove untracked files/directories (respects .gitignore, no -x flag)
            int cleanRc = run(workspaceDir, "git", "clean", "-fd");
            if (cleanRc != 0) {
                log.error("git clean -fd failed (rc={}) for workspace {}", cleanRc, workspaceDir);
                return false;
            }

            if (!CLEAN_SENTINEL.equals(snapshotRef)) {
                // Apply the stash to restore the original dirty state
                int applyRc = run(workspaceDir, "git", "stash", "apply", snapshotRef);
                if (applyRc != 0) {
                    log.error("git stash apply {} failed (rc={}) for workspace {}",
                            snapshotRef, applyRc, workspaceDir);
                    return false;
                }
            }

            deleteRefFile(workspaceDir);
            log.info("Reverted workspace {} to snapshot {}", workspaceDir, snapshotRef);
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Failed to revert workspace {} to snapshot {}: {}",
                    workspaceDir, snapshotRef, e.getMessage());
            return false;
        }
    }

    /**
     * Drop a snapshot that is no longer needed (e.g. after successful completion).
     *
     * <p>Removes the stash entry to prevent accumulation and cleans up the ref file.
     *
     * @param workspaceDir absolute path to the workspace root
     * @param snapshotRef  the ref returned by {@link #createSnapshot(String)}
     */
    public void dropSnapshot(String workspaceDir, String snapshotRef) {
        try {
            if (!CLEAN_SENTINEL.equals(snapshotRef)) {
                int rc = run(workspaceDir, "git", "stash", "drop", snapshotRef);
                if (rc != 0) {
                    log.warn("git stash drop {} failed (rc={}) for workspace {} — stash may already be gone",
                            snapshotRef, rc, workspaceDir);
                }
            }
            deleteRefFile(workspaceDir);
            log.debug("Dropped snapshot {} for workspace {}", snapshotRef, workspaceDir);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Failed to drop snapshot {} for workspace {}: {}",
                    snapshotRef, workspaceDir, e.getMessage());
        }
    }

    /**
     * Check whether the given directory is a git workspace.
     *
     * @param workspaceDir absolute path to check
     * @return {@code true} if a {@code .git} file or directory exists at the root
     */
    public boolean isGitWorkspace(String workspaceDir) {
        return new File(workspaceDir, ".git").exists();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void persistRef(String workspaceDir, String ref) throws IOException {
        Path sessionDir = Path.of(workspaceDir, SESSION_DIR);
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve(SNAPSHOT_REF_FILE), ref, StandardCharsets.UTF_8);
    }

    private void deleteRefFile(String workspaceDir) {
        try {
            Path refFile = Path.of(workspaceDir, SESSION_DIR, SNAPSHOT_REF_FILE);
            Files.deleteIfExists(refFile);
        } catch (IOException e) {
            log.debug("Could not delete snapshot ref file in {}: {}", workspaceDir, e.getMessage());
        }
    }

    private int run(String cwd, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(new File(cwd))
                .redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
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

    private String runAndCapture(String cwd, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(new File(cwd))
                .redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("git command timed out: " + String.join(" ", cmd));
            }
            int rc = p.exitValue();
            if (rc != 0) {
                log.debug("`{}` (cwd={}) rc={} output={}", String.join(" ", cmd), cwd, rc, sb);
            }
            return sb.toString().trim();
        }
    }
}
