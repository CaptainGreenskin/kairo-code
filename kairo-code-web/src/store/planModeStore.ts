import { create } from 'zustand';

/**
 * Plan mode state — Claude Code-style read-only research phase before write.
 *
 * Three independent fields:
 * - `pending`: user toggled plan mode for the *next* message; backend not yet aware.
 *   Cleared as soon as we send the message (with the plan-mode preamble injected).
 * - `active`: backend agent is currently inside plan mode (observed via enter_plan_mode
 *   tool call; cleared on exit_plan_mode approval). Read-only enforcement is on the
 *   server's DefaultToolExecutor — we just mirror the state for UI affordances.
 * - `awaitingApproval`: agent has submitted exit_plan_mode and is blocked on user.
 *   Set when a TOOL_CALL with toolName === 'exit_plan_mode' and requiresApproval=true
 *   arrives; cleared once approved/rejected.
 */
interface PlanModeState {
    pending: boolean;
    active: boolean;
    awaitingApproval: boolean;

    togglePending: () => void;
    setPending: (v: boolean) => void;
    setActive: (v: boolean) => void;
    setAwaitingApproval: (v: boolean) => void;
    reset: () => void;
}

export const usePlanModeStore = create<PlanModeState>((set) => ({
    pending: false,
    active: false,
    awaitingApproval: false,

    togglePending: () => set((s) => ({ pending: !s.pending })),
    setPending: (v) => set({ pending: v }),
    setActive: (v) => set({ active: v }),
    setAwaitingApproval: (v) => set({ awaitingApproval: v }),
    reset: () => set({ pending: false, active: false, awaitingApproval: false }),
}));

/**
 * Preamble to prepend to a user message when plan mode is requested for the next turn.
 * Mirrors Claude Code's plan-mode contract: research first, present plan via exit_plan_mode.
 */
export const PLAN_MODE_PREAMBLE =
    '<system-reminder>\n' +
    'Plan mode is active. Before doing anything, call the `enter_plan_mode` tool. ' +
    'Then research the codebase using read-only tools (Read, Grep, Glob, List). ' +
    'Do NOT write, edit, or run any side-effecting tools. ' +
    'When you have a complete plan, call `exit_plan_mode` with a clear `overview` and ' +
    '`plan_content` (markdown). The user will approve or reject the plan before any code is written.\n' +
    '</system-reminder>\n\n';
