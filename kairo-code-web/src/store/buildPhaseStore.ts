import { create } from 'zustand';
import type { PlanPhase } from '@/types/agent';

/**
 * Build phase state — tracks the plan lifecycle for the ConfirmBuildChip / RevertButton.
 *
 * Lifecycle: idle → PLAN_PENDING → EXECUTING → COMPLETED | FAILED_EXECUTION → (REVERTED)
 *
 * Driven by backend events:
 * - PLAN_READY  → sets phase to PLAN_PENDING
 * - confirmBuild action sent → sets phase to EXECUTING
 * - AGENT_DONE after EXECUTING → sets phase to COMPLETED
 * - AGENT_ERROR after EXECUTING → sets phase to FAILED_EXECUTION
 * - REVERTED event → sets phase to REVERTED, then idle
 */
interface BuildPhaseState {
    phase: PlanPhase;
    /** Whether the workspace has git initialised. Populated from SessionInfo. */
    isGit: boolean;

    setPhase: (phase: PlanPhase) => void;
    setIsGit: (v: boolean) => void;
    reset: () => void;
}

export const useBuildPhaseStore = create<BuildPhaseState>((set) => ({
    phase: 'idle',
    isGit: false,

    setPhase: (phase) => set({ phase }),
    setIsGit: (v) => set({ isGit: v }),
    reset: () => set({ phase: 'idle' }),
}));
