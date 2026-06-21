import { create } from 'zustand';
import { persist } from 'zustand/middleware';

/**
 * Tool-call approval mode.
 *
 *  - manual: every risky tool prompts for explicit approval
 *  - yolo:   auto-approve all tools (default for productive use)
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

const READ_SAFE_TOOLS = new Set([
    'read_file', 'list_dir', 'grep', 'glob', 'web_search', 'web_fetch',
    'search', 'find', 'list_files', 'read', 'skill_list',
]);

const FILE_WRITE_TOOLS = new Set([
    'write', 'edit', 'write_file', 'create_file', 'patch_file', 'apply_diff',
    'edit_file', 'str_replace_editor', 'multi_edit', 'search_replace',
    'batch_write', 'patch_apply', 'template_render',
]);

/**
 * Decide whether a tool call should be auto-approved given the current mode and tool name.
 * Returns `true` when the frontend should immediately call approveTool(true).
 *
 * `exit_plan_mode` is *never* auto-approved regardless of mode — its entire purpose is to
 * surface the proposed plan for user review before write-tools become available.
 *
 * Read-only tools and file-write tools are auto-approved in all modes.
 * File writes are non-blocking by design — users review changes via git diff
 * in the Source Control panel rather than approving each edit individually.
 *
 * Only shell/bash execution tools remain gated in manual mode.
 */
export function shouldAutoApprove(mode: ApprovalMode, toolName: string): boolean {
    if (toolName === 'exit_plan_mode') return false;
    if (READ_SAFE_TOOLS.has(toolName)) return true;
    if (FILE_WRITE_TOOLS.has(toolName)) return true;
    if (mode === 'yolo' || mode === 'auto-safe') return true;
    return false;
}
