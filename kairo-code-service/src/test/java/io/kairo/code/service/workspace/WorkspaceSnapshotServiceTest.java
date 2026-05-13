package io.kairo.code.service.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WorkspaceSnapshotService} using real temporary git repos.
 */
class WorkspaceSnapshotServiceTest {

    private WorkspaceSnapshotService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new WorkspaceSnapshotService();
    }

    private void initGitRepo(Path dir) throws Exception {
        run(dir, "git", "init");
        run(dir, "git", "config", "user.email", "test@test.com");
        run(dir, "git", "config", "user.name", "Test");
        // Create initial commit so HEAD exists
        Files.writeString(dir.resolve("README.md"), "init");
        run(dir, "git", "add", ".");
        run(dir, "git", "commit", "-m", "initial");
    }

    private void run(Path cwd, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        p.getInputStream().readAllBytes(); // drain
        int rc = p.waitFor();
        if (rc != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", cmd) + " rc=" + rc);
        }
    }

    @Test
    void createSnapshot_dirtyWorktree_returnsStashRef() throws Exception {
        initGitRepo(tempDir);
        // Make the worktree dirty by modifying an existing tracked file
        Files.writeString(tempDir.resolve("README.md"), "modified content");

        Optional<String> ref = service.createSnapshot(tempDir.toString());

        assertThat(ref).isPresent();
        assertThat(ref.get()).isNotEqualTo("CLEAN");
        // Should be a valid SHA-like string (40 hex chars)
        assertThat(ref.get()).matches("[0-9a-f]{40}");
    }

    @Test
    void createSnapshot_cleanWorktree_returnsCleanSentinel() throws Exception {
        initGitRepo(tempDir);

        Optional<String> ref = service.createSnapshot(tempDir.toString());

        assertThat(ref).isPresent();
        assertThat(ref.get()).isEqualTo("CLEAN");
    }

    @Test
    void revert_withStashRef_restoresOriginalState() throws Exception {
        initGitRepo(tempDir);
        // Create dirty state by modifying an existing tracked file
        Files.writeString(tempDir.resolve("README.md"), "original dirty content");

        Optional<String> ref = service.createSnapshot(tempDir.toString());
        assertThat(ref).isPresent();
        String snapshotRef = ref.get();
        assertThat(snapshotRef).isNotEqualTo("CLEAN");

        // Simulate build making different changes (overwrite in working tree)
        Files.writeString(tempDir.resolve("README.md"), "modified by build");
        Files.writeString(tempDir.resolve("buildfile.txt"), "build artifact");

        // Revert should restore the snapshot state ("original dirty content")
        boolean reverted = service.revert(tempDir.toString(), snapshotRef);
        assertThat(reverted).isTrue();
        // After revert: reset --hard HEAD (back to "init"), clean -fd, then stash apply
        // The stash had "original dirty content" - so that's what gets restored
        assertThat(Files.readString(tempDir.resolve("README.md"))).isEqualTo("original dirty content");
        // Build artifacts should be cleaned
        assertThat(Files.exists(tempDir.resolve("buildfile.txt"))).isFalse();
    }

    @Test
    void revert_withCleanSentinel_resetsWorktree() throws Exception {
        initGitRepo(tempDir);

        // Snapshot clean state
        Optional<String> ref = service.createSnapshot(tempDir.toString());
        assertThat(ref.get()).isEqualTo("CLEAN");

        // Create changes
        Files.writeString(tempDir.resolve("newfile.txt"), "should be gone");
        run(tempDir, "git", "add", "newfile.txt");

        // Revert with CLEAN sentinel — should reset, no stash apply
        boolean reverted = service.revert(tempDir.toString(), "CLEAN");
        assertThat(reverted).isTrue();
        assertThat(Files.exists(tempDir.resolve("newfile.txt"))).isFalse();
    }

    @Test
    void dropSnapshot_removesStashEntry() throws Exception {
        initGitRepo(tempDir);
        // Modify a tracked file to make it dirty
        Files.writeString(tempDir.resolve("README.md"), "dirty content");

        Optional<String> ref = service.createSnapshot(tempDir.toString());
        assertThat(ref).isPresent();
        String snapshotRef = ref.get();
        assertThat(snapshotRef).isNotEqualTo("CLEAN");

        // Drop should not throw
        service.dropSnapshot(tempDir.toString(), snapshotRef);

        // Snapshot ref file should be cleaned up
        assertThat(Files.exists(tempDir.resolve(".kairo-session/snapshot.ref"))).isFalse();
    }

    @Test
    void dropSnapshot_cleanSentinel_onlyDeletesRefFile() throws Exception {
        initGitRepo(tempDir);
        service.createSnapshot(tempDir.toString()); // CLEAN

        // Should not throw even though there's no real stash to drop
        service.dropSnapshot(tempDir.toString(), "CLEAN");

        assertThat(Files.exists(tempDir.resolve(".kairo-session/snapshot.ref"))).isFalse();
    }

    @Test
    void isGitWorkspace_gitDir_returnsTrue() throws Exception {
        initGitRepo(tempDir);
        assertThat(service.isGitWorkspace(tempDir.toString())).isTrue();
    }

    @Test
    void isGitWorkspace_nonGitDir_returnsFalse() {
        // tempDir has no .git directory
        Path nonGitDir = tempDir.resolve("plain");
        nonGitDir.toFile().mkdirs();
        assertThat(service.isGitWorkspace(nonGitDir.toString())).isFalse();
    }

    @Test
    void createSnapshot_persistsRefFile() throws Exception {
        initGitRepo(tempDir);
        Files.writeString(tempDir.resolve("README.md"), "dirty");

        service.createSnapshot(tempDir.toString());

        Path refFile = tempDir.resolve(".kairo-session/snapshot.ref");
        assertThat(Files.exists(refFile)).isTrue();
        String content = Files.readString(refFile).trim();
        assertThat(content).isNotBlank();
    }
}
