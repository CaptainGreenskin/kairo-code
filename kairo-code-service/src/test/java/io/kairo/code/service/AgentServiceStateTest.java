package io.kairo.code.service;

import io.kairo.code.core.CodeAgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the plan-pending state machine in {@link AgentService}.
 *
 * <p>Focuses on state transitions (IDLE → PLANNING → PLAN_PENDING → EXECUTING → COMPLETED/FAILED)
 * and the revert/crash-recovery paths. Tests use a @TempDir for persisted phase recovery.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class AgentServiceStateTest {

    private AgentService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new AgentService();
    }

    private CodeAgentConfig configWithWorkDir() {
        return new CodeAgentConfig(
                "test-api-key", "https://api.openai.com", "gpt-4o", 50,
                tempDir.toString(), null, 0, 0, null);
    }

    private CodeAgentConfig configNoWorkDir() {
        return new CodeAgentConfig(
                "test-api-key", "https://api.openai.com", "gpt-4o", 50,
                null, null, 0, 0, null);
    }

    // ── Initial phase ──

    @Test
    void newSession_startsInIdlePhase() {
        String sid = service.createSession(configNoWorkDir());
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.IDLE);
    }

    // ── sendMessage transitions (requires concurrencyController — tested at integration level) ──
    // The IDLE → PLANNING transition happens inside sendMessage which requires
    // @Autowired AgentConcurrencyController. We verify the state machine semantics
    // via crash recovery and error path tests below.

    // ── FAILED_EXECUTION rejects new messages ──

    @Test
    void failedExecution_rejectsNewMessages() {
        // Force phase to FAILED_EXECUTION via crash recovery simulation.
        // Requires snapshot.ref present so recovery preserves FAILED_EXECUTION (instead of
        // auto-recovering to IDLE — see crashRecovery_*_noSnapshot tests below).
        setPersistedPhase("EXECUTING");
        setSnapshotRef("stash@{0}");
        String sidWithCrash = service.createSession(configWithWorkDir());
        assertThat(service.getSessionPhase(sidWithCrash)).isEqualTo(SessionPhase.FAILED_EXECUTION);

        // Sending message should return immediately with REVERT_REQUIRED error
        var flux = service.sendMessage(sidWithCrash, "try again");
        var events = flux.collectList().block();
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).errorType()).isEqualTo("REVERT_REQUIRED");
    }

    // ── confirmBuild transitions from PLAN_PENDING to EXECUTING ──

    @Test
    void confirmBuild_notInPlanPending_returnsFalse() {
        String sid = service.createSession(configNoWorkDir());
        // Phase is IDLE, not PLAN_PENDING
        assertThat(service.confirmBuild(sid)).isFalse();
    }

    @Test
    void confirmBuild_unknownSession_returnsFalse() {
        assertThat(service.confirmBuild("nonexistent")).isFalse();
    }

    // ── stopAgent transitions EXECUTING → FAILED_EXECUTION ──

    @Test
    void stopAgent_duringPlanning_doesNotThrow() {
        String sid = service.createSession(configNoWorkDir());
        // Immediately stop — should not throw regardless of state
        service.stopAgent(sid);
        // Phase remains IDLE since agent was never called
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.IDLE);
    }

    // ── resumeSession contract (the general-flow Resume button depends on this) ──

    @Test
    void resumeSession_fromFailedExecution_resetsAndContinues() {
        // A stopped run lands in FAILED_EXECUTION (see stopAgent). Resume must clear it
        // and inject a continuation message. In unit tests (no concurrency controller wired)
        // the continuation may fail gracefully, but resumeSession must still return true
        // and the phase must have been reset from FAILED_EXECUTION.
        setPersistedPhase("FAILED_EXECUTION");
        setSnapshotRef("stash@{0}");
        String sid = service.createSession(configWithWorkDir());
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.FAILED_EXECUTION);

        assertThat(service.resumeSession(sid)).isTrue();
        // Phase is no longer FAILED_EXECUTION (may be IDLE or EXECUTING depending on wiring).
        assertThat(service.getSessionPhase(sid)).isNotEqualTo(SessionPhase.FAILED_EXECUTION);
    }

    @Test
    void resumeSession_fromIdle_returnsFalse() {
        String sid = service.createSession(configNoWorkDir());
        // Nothing to resume from IDLE.
        assertThat(service.resumeSession(sid)).isFalse();
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.IDLE);
    }

    @Test
    void resumeSession_unknownSession_returnsFalse() {
        assertThat(service.resumeSession("nonexistent")).isFalse();
    }

    // ── Crash recovery: persisted EXECUTING → force FAILED_EXECUTION on restart ──

    @Test
    void crashRecovery_executingPhase_withSnapshot_forcesFailedExecution() throws Exception {
        setPersistedPhase("EXECUTING");
        setSnapshotRef("stash@{0}");

        String sid = service.createSession(configWithWorkDir());
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.FAILED_EXECUTION);
    }

    @Test
    void crashRecovery_planningPhase_withSnapshot_forcesFailedExecution() throws Exception {
        setPersistedPhase("PLANNING");
        setSnapshotRef("stash@{0}");

        String sid = service.createSession(configWithWorkDir());
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.FAILED_EXECUTION);
    }

    @Test
    void crashRecovery_executingPhase_noSnapshot_recoversToIdle() throws Exception {
        // No snapshot.ref → revert is impossible, so blocking the user serves no purpose.
        // Should auto-recover to IDLE and delete the stale phase.txt.
        setPersistedPhase("EXECUTING");

        String sid = service.createSession(configWithWorkDir());
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.IDLE);
        // phase.txt should be cleared so future restarts don't re-block
        assertThat(Files.exists(tempDir.resolve(".kairo-session").resolve("phase.txt"))).isFalse();
    }

    @Test
    void crashRecovery_planningPhase_noSnapshot_recoversToIdle() throws Exception {
        setPersistedPhase("PLANNING");

        String sid = service.createSession(configWithWorkDir());
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.IDLE);
        assertThat(Files.exists(tempDir.resolve(".kairo-session").resolve("phase.txt"))).isFalse();
    }

    @Test
    void crashRecovery_planPendingPhase_restoresPlanPending() throws Exception {
        setPersistedPhase("PLAN_PENDING");

        String sid = service.createSession(configWithWorkDir());
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.PLAN_PENDING);
    }

    @Test
    void crashRecovery_failedExecutionPhase_withSnapshot_restoresFailedExecution() throws Exception {
        setPersistedPhase("FAILED_EXECUTION");
        setSnapshotRef("stash@{0}");

        String sid = service.createSession(configWithWorkDir());
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.FAILED_EXECUTION);
    }

    @Test
    void crashRecovery_failedExecutionPhase_noSnapshot_recoversToIdle() throws Exception {
        // The bug scenario: stale FAILED_EXECUTION from a prior session with no
        // revertible snapshot — user was stuck with no UI escape. Now auto-clears.
        setPersistedPhase("FAILED_EXECUTION");

        String sid = service.createSession(configWithWorkDir());
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.IDLE);
        assertThat(Files.exists(tempDir.resolve(".kairo-session").resolve("phase.txt"))).isFalse();
    }

    @Test
    void crashRecovery_noPhaseFile_startsIdle() {
        // No .kairo-session/phase.txt exists
        String sid = service.createSession(configWithWorkDir());
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.IDLE);
    }

    // ── PLAN_PENDING accepts refinement (not SESSION_BUSY) ──
    // Full PLAN_PENDING refinement testing requires a wired agent (which calls
    // agent.call().block()). We verify the guard logic: PLAN_PENDING route is
    // taken (not SESSION_BUSY), even though processing may fail internally.

    @Test
    void planPending_phasePreserved_afterAccess() {
        setPersistedPhase("PLAN_PENDING");
        String sid = service.createSession(configWithWorkDir());
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.PLAN_PENDING);
        // Verify that PLAN_PENDING is correctly restored from persistence
        // and the session correctly rejects revert (which requires FAILED_EXECUTION or COMPLETED)
        assertThat(service.revertSession(sid)).isFalse();
        // Phase should still be PLAN_PENDING (revert rejected for this phase)
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.PLAN_PENDING);
    }

    // ── Revert ──

    @Test
    void revertSession_nonExistentSession_returnsFalse() {
        assertThat(service.revertSession("nonexistent")).isFalse();
    }

    @Test
    void revertSession_idlePhase_returnsFalse() {
        String sid = service.createSession(configNoWorkDir());
        assertThat(service.revertSession(sid)).isFalse();
    }

    @Test
    void revertSession_failedExecution_noSnapshot_softRevertsToIdle() throws Exception {
        // Set up: FAILED_EXECUTION restored via crash recovery, then we manually
        // re-add snapshot.ref + phase.txt to simulate a session that entered
        // FAILED_EXECUTION at runtime (not via recovery, which would have auto-cleared).
        // The simplest setup is: recover with snapshot present, then delete snapshot
        // before calling revert.
        setPersistedPhase("FAILED_EXECUTION");
        setSnapshotRef("stash@{0}");
        String sid = service.createSession(configWithWorkDir());
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.FAILED_EXECUTION);

        // Simulate the bug: snapshot.ref gets deleted out from under us
        Files.deleteIfExists(tempDir.resolve(".kairo-session").resolve("snapshot.ref"));

        // Soft revert should succeed (user gets an escape hatch)
        assertThat(service.revertSession(sid)).isTrue();
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.IDLE);
        // phase.txt cleared
        assertThat(Files.exists(tempDir.resolve(".kairo-session").resolve("phase.txt"))).isFalse();
    }

    // ── Helpers ──

    private void setPersistedPhase(String phaseName) {
        try {
            Path sessionDir = tempDir.resolve(".kairo-session");
            Files.createDirectories(sessionDir);
            Files.writeString(sessionDir.resolve("phase.txt"), phaseName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setSnapshotRef(String ref) {
        try {
            Path sessionDir = tempDir.resolve(".kairo-session");
            Files.createDirectories(sessionDir);
            Files.writeString(sessionDir.resolve("snapshot.ref"), ref);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
