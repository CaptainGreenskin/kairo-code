export type RiskLevel = 'safe' | 'caution' | 'danger';

const DANGER_TOOLS = new Set([
    'bash', 'shell', 'execute', 'run_command', 'execute_command',
    'delete_file', 'remove_file', 'rm',
]);

const CAUTION_TOOLS = new Set([
    'write_file', 'create_file', 'overwrite_file', 'edit_file',
    'patch_file', 'str_replace_editor', 'insert_content',
]);

export function getToolRisk(toolName: string): RiskLevel {
    const lower = toolName.toLowerCase();
    if (DANGER_TOOLS.has(lower)) return 'danger';
    if (CAUTION_TOOLS.has(lower)) return 'caution';
    return 'safe';
}

export const RISK_LABELS: Record<RiskLevel, string> = {
    safe: '',
    caution: 'writes files',
    danger: 'executes code',
};

export const RISK_COLORS: Record<RiskLevel, string> = {
    safe: 'border-[var(--border)]',
    caution: 'border-amber-500/50',
    danger: 'border-red-500/60',
};

export const RISK_BADGE_COLORS: Record<RiskLevel, string> = {
    safe: '',
    caution: 'bg-amber-500/15 text-amber-400',
    danger: 'bg-red-500/15 text-red-400',
};
