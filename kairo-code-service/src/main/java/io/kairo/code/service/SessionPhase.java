package io.kairo.code.service;

/**
 * State machine for a session's lifecycle in plan-pending mode.
 *
 * <pre>
 * IDLE ──sendMessage──▸ PLANNING ──exitPlanMode hook──▸ PLAN_PENDING
 *                                                            │
 *                                     confirmBuild ◀─────────┘
 *                                          │
 *                                          ▼
 *                                      EXECUTING ──done──▸ COMPLETED
 *                                          │
 *                                       stop()/error
 *                                          │
 *                                          ▼
 *                                   FAILED_EXECUTION
 *
 * PLANNING ──error──▸ FAILED_PLANNING (retryable, worktree clean)
 * FAILED_EXECUTION ──revert──▸ IDLE (revert required before retry)
 * </pre>
 */
public enum SessionPhase {

    /** No active operation. Session accepts new messages. */
    IDLE,

    /** Agent is generating a plan. Messages rejected as SESSION_BUSY. */
    PLANNING,

    /**
     * Plan generated, awaiting explicit user confirmation via {@code confirmBuild}.
     * User messages during this phase are routed to the plan agent for refinement
     * (not rejected as SESSION_BUSY).
     */
    PLAN_PENDING,

    /** Plan confirmed; agent is executing the plan. Messages rejected as SESSION_BUSY. */
    EXECUTING,

    /** Plan executed successfully. */
    COMPLETED,

    /**
     * Planning failed (worktree is clean). Accepts retry messages — transitions back to PLANNING.
     */
    FAILED_PLANNING,

    /**
     * Execution failed or was interrupted (worktree may have half-baked changes).
     * Rejects messages until revert. UI shows "Revert first to retry".
     */
    FAILED_EXECUTION
}
