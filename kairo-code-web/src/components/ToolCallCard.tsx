import { useState } from 'react';
import { Check, X, Loader2, AlertCircle } from 'lucide-react';
import type { ToolCall } from '@/types/agent';
import { TerminalOutput } from './TerminalOutput';
import { FileDiffView } from './FileDiffView';

interface ToolCallCardProps {
    toolCall: ToolCall;
    onApprove?: (toolCallId: string, approved: boolean) => void;
}

const statusConfig: Record<ToolCall['status'], { label: string; color: string; icon: React.ReactNode }> = {
    pending: {
        label: 'Pending',
        color: 'text-[var(--color-warning)]',
        icon: <Loader2 size={14} className="animate-spin" />,
    },
    approved: {
        label: 'Approved',
        color: 'text-[var(--color-info)]',
        icon: <Loader2 size={14} className="animate-spin" />,
    },
    rejected: {
        label: 'Rejected',
        color: 'text-[var(--color-danger)]',
        icon: <X size={14} />,
    },
    done: {
        label: 'Done',
        color: 'text-[var(--color-success)]',
        icon: <Check size={14} />,
    },
    error: {
        label: 'Error',
        color: 'text-[var(--color-danger)]',
        icon: <AlertCircle size={14} />,
    },
};

/**
 * Extract original content from unified diff: lines starting with '-' (excluding '---').
 */
function extractOriginal(diff: string): string {
    const lines = diff.split('\n');
    const original: string[] = [];
    let inHunk = false;
    for (const line of lines) {
        if (line.startsWith('@@')) {
            inHunk = true;
            continue;
        }
        if (inHunk) {
            if (line.startsWith('---') || line.startsWith('+++')) continue;
            if (line.startsWith('-')) {
                original.push(line.slice(1));
            } else if (line.startsWith(' ')) {
                original.push(line.slice(1));
            }
        }
    }
    return original.length > 0 ? original.join('\n') : '';
}

/**
 * Extract modified content from unified diff: lines starting with '+' (excluding '+++').
 */
function extractModified(diff: string): string {
    const lines = diff.split('\n');
    const modified: string[] = [];
    let inHunk = false;
    for (const line of lines) {
        if (line.startsWith('@@')) {
            inHunk = true;
            continue;
        }
        if (inHunk) {
            if (line.startsWith('---') || line.startsWith('+++')) continue;
            if (line.startsWith('+')) {
                modified.push(line.slice(1));
            } else if (line.startsWith(' ')) {
                modified.push(line.slice(1));
            }
        }
    }
    return modified.length > 0 ? modified.join('\n') : '';
}

export function ToolCallCard({ toolCall, onApprove }: ToolCallCardProps) {
    const config = statusConfig[toolCall.status];
    const [expanded, setExpanded] = useState(false);

    return (
        <div className="my-2 border border-[var(--border)] rounded-lg overflow-hidden bg-[var(--bg-secondary)]">
            <div className="px-3 py-2 flex items-center justify-between">
                <div className="flex items-center gap-2">
                    <code className="text-sm font-mono font-medium text-[var(--text-primary)]">
                        {toolCall.toolName}
                    </code>
                    <span className={`flex items-center gap-1 text-xs ${config.color}`}>
                        {config.icon}
                        {config.label}
                    </span>
                </div>
                {toolCall.durationMs !== undefined && toolCall.status === 'done' && (
                    <span className="text-xs text-[var(--text-muted)]">
                        {(toolCall.durationMs / 1000).toFixed(1)}s
                    </span>
                )}
            </div>

            <button
                onClick={() => setExpanded((prev) => !prev)}
                className="w-full px-3 py-1.5 text-left text-xs text-[var(--text-secondary)] hover:bg-[var(--bg-hover)] transition-colors border-t border-[var(--border)]"
            >
                {expanded ? 'Hide' : 'Show'} input
            </button>

            {expanded && (
                <pre className="px-3 py-2 text-xs font-mono text-[var(--text-secondary)] overflow-x-auto bg-[var(--code-bg)] max-h-48">
                    {JSON.stringify(toolCall.input, null, 2)}
                </pre>
            )}

            {toolCall.result && toolCall.status === 'done' && (
                <div className="border-t border-[var(--border)]">
                    {toolCall.toolName === 'bash' ? (
                        <TerminalOutput output={toolCall.result} />
                    ) : toolCall.toolName === 'write_file' ? (
                        <FileDiffView
                            fileName={(toolCall.input as { path?: string })?.path || 'file'}
                            original=""
                            modified={toolCall.result}
                            mode="preview"
                        />
                    ) : toolCall.toolName === 'patch_apply' ? (
                        <FileDiffView
                            fileName={(toolCall.input as { path?: string })?.path || 'file'}
                            original={extractOriginal(toolCall.result)}
                            modified={extractModified(toolCall.result)}
                        />
                    ) : (
                        <pre className="px-3 py-2 text-xs font-mono text-[var(--text-secondary)] overflow-x-auto bg-[var(--code-bg)] max-h-48">
                            {toolCall.result}
                        </pre>
                    )}
                </div>
            )}

            {toolCall.requiresApproval && toolCall.status === 'pending' && onApprove && (
                <div className="px-3 py-2 flex items-center gap-2 border-t border-[var(--border)]">
                    <button
                        onClick={() => onApprove(toolCall.id, true)}
                        className="flex items-center gap-1 px-3 py-1 text-xs font-medium text-white bg-[var(--color-success)] hover:bg-[var(--color-success)]/90 rounded transition-colors"
                    >
                        <Check size={12} />
                        Approve
                    </button>
                    <button
                        onClick={() => onApprove(toolCall.id, false)}
                        className="flex items-center gap-1 px-3 py-1 text-xs font-medium text-white bg-[var(--color-danger)] hover:bg-[var(--color-danger)]/90 rounded transition-colors"
                    >
                        <X size={12} />
                        Reject
                    </button>
                </div>
            )}
        </div>
    );
}
