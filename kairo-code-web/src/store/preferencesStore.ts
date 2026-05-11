import { create } from 'zustand';
import { persist } from 'zustand/middleware';

/**
 * Tool-call approval mode.
 *
 *  - manual:    every risky tool (bash / write_file / edit_file) prompts for explicit approval (default)
 *  - auto-safe: read-only/write tools auto-approve; bash still prompts (recommended)
 *  - yolo:      auto-approve every risky tool — destructive, only enable in trusted workspaces
 */
export type ApprovalMode = 'manual' | 'auto-safe' | 'yolo';

interface PreferencesState {
    approvalMode: ApprovalMode;
    setApprovalMode: (mode: ApprovalMode) => void;
}

export const usePreferencesStore = create<PreferencesState>()(
    persist(
        (set) => ({
            approvalMode: 'manual',
            setApprovalMode: (approvalMode) => set({ approvalMode }),
        }),
        { name: 'kairo-preferences' },
    ),
);

/**
 * Decide whether a tool call should be auto-approved given the current mode and tool name.
 * Returns `true` when the frontend should immediately call approveTool(true).
 *
 * `exit_plan_mode` is *never* auto-approved regardless of mode — its entire purpose is to
 * surface the proposed plan for user review before write-tools become available.
 */
export function shouldAutoApprove(mode: ApprovalMode, toolName: string): boolean {
    if (toolName === 'exit_plan_mode') return false;
    if (mode === 'yolo') return true;
    if (mode === 'auto-safe') {
        // bash is the only thing that can run arbitrary shell commands; keep it gated
        return toolName !== 'bash';
    }
    return false;
}
