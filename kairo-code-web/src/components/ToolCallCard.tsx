import { useState, useEffect, useRef, useCallback } from 'react';
import { Check, X, Loader2, AlertCircle } from 'lucide-react';
import type { ToolCall } from '@/types/agent';
import { TerminalOutput } from './TerminalOutput';
import { FileDiffView } from './FileDiffView';
import { getToolRisk, RISK_LABELS, RISK_COLORS, RISK_BADGE_COLORS } from '@utils/toolRisk';

interface ToolCallCardProps {
    toolCall: ToolCall;
    onApprove?: (toolCallId: string, approved: boolean) => void;
    /** Timeout in seconds before auto-reject. Default: 120. */
    approvalTimeout?: number;
}

const MAX_PREVIEW = 200;

interface ResultOutputProps {
    toolName: string;
    result: string;
    input: Record<string, unknown>;
}

function ResultOutput({ toolName, result, input }: ResultOutputProps) {
    const [expanded, setExpanded] = useState(false);
    const preview = result.slice(0, MAX_PREVIEW);
    const hasMore = result.length > MAX_PREVIEW;

    if (toolName === 'bash') {
        return (
            <div className="border-t border-[var(--border)]">
                <div className="relative">
                    <TerminalOutput output={expanded ? result : preview} />
                    {hasMore && !expanded && (
                        <div className="absolute bottom-0 left-0 right-0 h-8 bg-gradient-to-t from-[var(--code-bg)] to-transparent" />
                    )}
                </div>
                {hasMore && (
                    <button
                        onClick={() => setExpanded((prev) => !prev)}
                        className="w-full px-3 py-1 text-[10px] text-[var(--accent)] hover:underline text-left border-t border-[var(--border)]"
                    >
                        {expanded ? 'Show less' : `Show ${result.length - MAX_PREVIEW} more chars…`}
                    </button>
                )}
            </div>
        );
    }

    if (toolName === 'write_file') {
        return (
            <div className="border-t border-[var(--border)]">
                <FileDiffView
                    fileName={(input as { path?: string })?.path || 'file'}
                    original=""
                    modified={expanded ? result : preview}
                    mode="preview"
                />
                {hasMore && (
                    <button
                        onClick={() => setExpanded((prev) => !prev)}
                        className="w-full px-3 py-1 text-[10px] text-[var(--accent)] hover:underline text-left border-t border-[var(--border)]"
                    >
                        {expanded ? 'Show less' : `Show ${result.length - MAX_PREVIEW} more chars…`}
                    </button>
                )}
            </div>
        );
    }

    if (toolName === 'patch_apply') {
        return (
            <div className="border-t border-[var(--border)]">
                <FileDiffView
                    fileName={(input as { path?: string })?.path || 'file'}
                    original={extractOriginal(result)}
                    modified={extractModified(result)}
                />
            </div>
        );
    }

    // Generic tool: show raw output with expand/collapse
    return (
        <div className="border-t border-[var(--border)]">
            <pre
                className={`px-3 py-2 text-xs font-mono text-[var(--text-secondary)] overflow-x-auto bg-[var(--code-bg)] whitespace-pre-wrap break-all ${
                    !expanded && hasMore ? 'line-clamp-3' : 'max-h-96 overflow-auto'
                }`}
            >
                {expanded ? result : preview}
            </pre>
            {hasMore && (
                <button
                    onClick={() => setExpanded((prev) => !prev)}
                    className="w-full px-3 py-1 text-[10px] text-[var(--accent)] hover:underline text-left border-t border-[var(--border)]"
                >
                    {expanded ? 'Show less' : `Show ${result.length - MAX_PREVIEW} more chars…`}
                </button>
            )}
        </div>
    );
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

export function ToolCallCard({ toolCall, onApprove, approvalTimeout = 120 }: ToolCallCardProps) {
    const config = statusConfig[toolCall.status];
    const risk = getToolRisk(toolCall.toolName);
    const riskLabel = RISK_LABELS[risk];
    const [expanded, setExpanded] = useState(false);

    // Timeout countdown for pending tool calls
    const [timeRemaining, setTimeRemaining] = useState(approvalTimeout);
    const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
    const isPending = toolCall.requiresApproval && toolCall.status === 'pending';

    // Start countdown when the card is pending
    useEffect(() => {
        if (!isPending) return;
        setTimeRemaining(approvalTimeout);
        timerRef.current = setInterval(() => {
            setTimeRemaining((prev) => {
                if (prev <= 1) {
                    if (timerRef.current) clearInterval(timerRef.current);
                    return 0;
                }
                return prev - 1;
            });
        }, 1000);
        return () => {
            if (timerRef.current) clearInterval(timerRef.current);
        };
    }, [isPending, approvalTimeout]);

    // Auto-reject on timeout
    const hasTimedOut = timeRemaining === 0 && isPending;
    useEffect(() => {
        if (hasTimedOut && onApprove) {
            onApprove(toolCall.id, false);
        }
    }, [hasTimedOut, onApprove, toolCall.id]);

    // Keyboard shortcuts: y = approve, n = reject
    const handleKeyDown = useCallback(
        (e: KeyboardEvent) => {
            if (!isPending || !onApprove) return;
            if (e.key === 'y' || e.key === 'Y') {
                e.preventDefault();
                onApprove(toolCall.id, true);
            } else if (e.key === 'n' || e.key === 'N') {
                e.preventDefault();
                onApprove(toolCall.id, false);
            }
        },
        [isPending, onApprove, toolCall.id],
    );

    useEffect(() => {
        if (!isPending) return;
        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, [isPending, handleKeyDown]);

    // Progress percentage
    const progressPct = isPending ? ((approvalTimeout - timeRemaining) / approvalTimeout) * 100 : 0;

    // Format remaining time
    const formatTime = (seconds: number): string => {
        const m = Math.floor(seconds / 60);
        const s = seconds % 60;
        return m > 0 ? `${m}:${s.toString().padStart(2, '0')}` : `${s}s`;
    };

    return (
        <div className={`my-2 border ${RISK_COLORS[risk]} rounded-lg overflow-hidden bg-[var(--bg-secondary)]`}>
            <div className="px-3 py-2 flex items-center justify-between">
                <div className="flex items-center gap-2">
                    <code className="text-sm font-mono font-medium text-[var(--text-primary)]">
                        {toolCall.toolName}
                    </code>
                    {riskLabel && toolCall.status === 'pending' && (
                        <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${RISK_BADGE_COLORS[risk]}`}>
                            {riskLabel}
                        </span>
                    )}
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
                <ResultOutput
                    toolName={toolCall.toolName}
                    result={toolCall.result}
                    input={toolCall.input}
                />
            )}

            {isPending && onApprove && (
                <div className="px-3 py-2 border-t border-[var(--border)]">
                    <div className="flex items-center gap-2 mb-2">
                        <button
                            onClick={() => onApprove(toolCall.id, true)}
                            className={`flex items-center gap-1 px-3 py-1 text-xs font-medium text-white rounded transition-colors ${
                                risk === 'danger'
                                    ? 'bg-red-600 hover:bg-red-700'
                                    : risk === 'caution'
                                    ? 'bg-amber-600 hover:bg-amber-700'
                                    : 'bg-[var(--color-success)] hover:bg-[var(--color-success)]/90'
                            }`}
                        >
                            <Check size={12} />
                            {risk === 'danger' ? 'Run' : 'Approve'}
                        </button>
                        <button
                            onClick={() => onApprove(toolCall.id, false)}
                            className="flex items-center gap-1 px-3 py-1 text-xs font-medium text-white bg-[var(--color-danger)] hover:bg-[var(--color-danger)]/90 rounded transition-colors"
                        >
                            <X size={12} />
                            Reject
                        </button>
                        {/* Timeout countdown */}
                        <div className="ml-auto flex items-center gap-2">
                            <span className="text-xs text-[var(--text-muted)] font-mono">
                                {formatTime(timeRemaining)}
                            </span>
                            <div className="w-20 h-1.5 bg-[var(--bg-tertiary)] rounded-full overflow-hidden">
                                <div
                                    className="h-full bg-[var(--color-warning)] transition-all duration-1000"
                                    style={{ width: `${100 - progressPct}%` }}
                                />
                            </div>
                        </div>
                    </div>
                    {/* Keyboard shortcut hints */}
                    <div className="text-[10px] text-[var(--text-muted)]">
                        <kbd className="px-1 py-0.5 bg-[var(--bg-tertiary)] rounded text-[10px]">y</kbd>
                        {' '}approve{' · '}
                        <kbd className="px-1 py-0.5 bg-[var(--bg-tertiary)] rounded text-[10px]">n</kbd>
                        {' '}reject
                    </div>
                </div>
            )}
        </div>
    );
}
