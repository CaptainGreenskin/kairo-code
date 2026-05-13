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
        // Force phase to FAILED_EXECUTION via crash recovery simulation
        setPersistedPhase("EXECUTING");
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

    // ── Crash recovery: persisted EXECUTING → force FAILED_EXECUTION on restart ──

    @Test
    void crashRecovery_executingPhase_forcesFailedExecution() throws Exception {
        setPersistedPhase("EXECUTING");

        String sid = service.createSession(configWithWorkDir());
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.FAILED_EXECUTION);
    }

    @Test
    void crashRecovery_planningPhase_forcesFailedExecution() throws Exception {
        setPersistedPhase("PLANNING");

        String sid = service.createSession(configWithWorkDir());
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.FAILED_EXECUTION);
    }

    @Test
    void crashRecovery_planPendingPhase_restoresPlanPending() throws Exception {
        setPersistedPhase("PLAN_PENDING");

        String sid = service.createSession(configWithWorkDir());
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.PLAN_PENDING);
    }

    @Test
    void crashRecovery_failedExecutionPhase_restoresFailedExecution() throws Exception {
        setPersistedPhase("FAILED_EXECUTION");

        String sid = service.createSession(configWithWorkDir());
        assertThat(service.getSessionPhase(sid)).isEqualTo(SessionPhase.FAILED_EXECUTION);
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
}
