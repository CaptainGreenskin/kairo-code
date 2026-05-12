import { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { Check, X, Loader2, AlertCircle, ClipboardList, Plus, Trash2, ChevronDown, ChevronUp } from 'lucide-react';
import type { PlanItem, ToolCall } from '@/types/agent';
import { TerminalOutput } from './TerminalOutput';
import { FileDiffView } from './FileDiffView';
import { getToolRisk, RISK_LABELS, RISK_COLORS, RISK_BADGE_COLORS } from '@utils/toolRisk';
import { extractFileWriteInfo, getToolRiskLevel } from '@utils/toolPreview';
import { FileContentPreview } from './FileContentPreview';
import { InlineTodoCard, parseTodoWriteInput } from './InlineTodoCard';
import { LazyMarkdown } from './LazyMarkdown';

const FILE_WRITE_TOOLS = new Set([
    'write', 'edit', 'multi_edit',
    'write_file', 'create_file', 'patch_file', 'apply_diff', 'edit_file', 'str_replace_editor',
]);

const FILE_READ_TOOLS = new Set([
    'read', 'read_file', 'cat', 'view',
]);

const BASH_TOOLS = new Set(['bash', 'shell', 'execute', 'run_command', 'terminal']);

const COLLAPSE_LINES = 5;

/**
 * Generate a short preview string for a completed tool result.
 * Returns a truncated one-liner suitable for display in the collapsed card header.
 */
function getCollapsedPreview(
    toolName: string,
    input: Record<string, unknown> | undefined,
    result: string | undefined,
): string {
    if (!result) return '';

    // File write tools: show "→ filepath"
    if (FILE_WRITE_TOOLS.has(toolName)) {
        const path = (input as { path?: string; file?: string; file_path?: string })?.path
            ?? (input as { file?: string })?.file
            ?? (input as { file_path?: string })?.file_path
            ?? '';
        if (path) return `→ ${path}`;
    }

    // Bash tools: show first line of stdout
    if (BASH_TOOLS.has(toolName)) {
        const firstLine = result.split('\n').find(l => l.trim());
        if (firstLine) {
            return firstLine.length > 60 ? firstLine.slice(0, 57) + '…' : firstLine;
        }
        return '';
    }

    // Read tools: show "Read filepath (N lines)"
    if (FILE_READ_TOOLS.has(toolName)) {
        const path = (input as { path?: string; file?: string; file_path?: string })?.path
            ?? (input as { file?: string })?.file
            ?? (input as { file_path?: string })?.file_path
            ?? 'file';
        const lineCount = result.split('\n').length;
        return `Read ${path} (${lineCount} lines)`;
    }

    // todo_write: show "Plan: N items, X done"
    if (toolName === 'todo_write') {
        const { todos } = parseTodoWriteInput(input);
        if (todos.length > 0) {
            const doneCount = todos.filter(t => t.status === 'completed').length;
            return `Plan: ${todos.length} items, ${doneCount} done`;
        }
    }

    // Generic: first meaningful line of result
    const firstLine = result.split('\n').find(l => l.trim());
    if (firstLine) {
        return firstLine.length > 60 ? firstLine.slice(0, 57) + '…' : firstLine;
    }
    return '';
}

/**
 * Drop the synthetic `_streaming_result` marker from tool input. The backend uses it to feed
 * eager-streaming tool results back into the model loop; legacy snapshots written before the
 * cleanup landed still carry the marker, so guard at render time.
 */
function stripStreamingMarker(input: unknown): unknown {
    if (!input || typeof input !== 'object' || Array.isArray(input)) return input;
    const rec = input as Record<string, unknown>;
    if (!('_streaming_result' in rec)) return input;
    const { _streaming_result: _ignored, ...rest } = rec;
    return rest;
}

/**
 * Parse the {@code items} field from an exit_plan_mode tool input. Backend accepts both
 * a JSON array and a stringified JSON array — we mirror that here so the approval card
 * shows real items regardless of which shape the model picked.
 */
function parseExitPlanItems(input: Record<string, unknown> | undefined): PlanItem[] {
    if (!input) return [];
    let raw: unknown = input.items;
    if (typeof raw === 'string') {
        const trimmed = raw.trim();
        if (!trimmed) return [];
        try {
            raw = JSON.parse(trimmed);
        } catch {
            return [];
        }
    }
    if (!Array.isArray(raw)) return [];
    const result: PlanItem[] = [];
    for (const elem of raw) {
        if (!elem || typeof elem !== 'object') continue;
        const obj = elem as Record<string, unknown>;
        const content = typeof obj.content === 'string' ? obj.content.trim() : '';
        if (!content) continue;
        const priorityRaw = typeof obj.priority === 'string' ? obj.priority : undefined;
        const priority =
            priorityRaw === 'high' || priorityRaw === 'medium' || priorityRaw === 'low'
                ? (priorityRaw as PlanItem['priority'])
                : undefined;
        result.push({ content, ...(priority ? { priority } : {}) });
    }
    return result;
}

/** Check if a string contains unified diff content. */
function hasUnifiedDiff(text: string): boolean {
    return /^\-\-\- a\//m.test(text) || /^\-\-\- \S/m.test(text);
}

/** Extract the file path from unified diff header. */
function extractDiffFilePath(text: string): string {
    const match = text.match(/^\-\-\- a\/(.+)/m) || text.match(/^\+\+\+ b\/(.+)/m);
    return match ? match[1] : 'file';
}

/** Split result into lines; return { visibleLines, hiddenCount } based on collapse state. */
function collapseLines(result: string, expanded: boolean): { lines: string[]; hiddenCount: number } {
    const lines = result.split('\n');
    if (expanded || lines.length <= COLLAPSE_LINES) {
        return { lines, hiddenCount: 0 };
    }
    return { lines: lines.slice(0, COLLAPSE_LINES), hiddenCount: lines.length - COLLAPSE_LINES };
}

/**
 * Renders a typed chip when a TOOL_RESULT carries a {@code failureReason} (mirrors backend
 * {@code FailureReason} enum). Without this, every non-success tool result rendered as an
 * undifferentiated red error — a timeout looked the same as a handler exception, making
 * long-task incidents (the original {@code exit_plan_mode} hang) hard to triage from the UI.
 */
function FailureReasonChip({ toolCall }: { toolCall: ToolCall }) {
    if (!toolCall.failureReason) return null;
    const cfg = {
        TIMEOUT:        { label: 'timed out',  cls: 'bg-amber-500/15 text-amber-400 border-amber-500/30' },
        USER_CANCELLED: { label: 'cancelled',  cls: 'bg-slate-500/15 text-slate-400 border-slate-500/30' },
        INTERRUPTED:    { label: 'interrupted',cls: 'bg-slate-500/15 text-slate-400 border-slate-500/30' },
        HANDLER_ERROR:  { label: 'error',      cls: 'bg-red-500/15 text-red-400 border-red-500/30' },
        VALIDATION:     { label: 'invalid',    cls: 'bg-rose-500/15 text-rose-400 border-rose-500/30' },
    }[toolCall.failureReason];
    return (
        <span
            className={`text-[10px] px-1.5 py-0.5 rounded border font-mono ${cfg.cls}`}
            title={`failureReason: ${toolCall.failureReason}`}
        >
            {cfg.label}
        </span>
    );
}

/**
 * Live waiting indicator. The backend emits TOOL_PROGRESS heartbeats (~5s) for any tool
 * still in flight after a 30s threshold; we show elapsed time + phase so the user can tell
 * "still alive, waiting for approval" from "stuck/hung".
 */
function ProgressChip({ toolCall }: { toolCall: ToolCall }) {
    if (toolCall.status === 'done' || toolCall.status === 'error' || toolCall.status === 'rejected') {
        return null;
    }
    if (toolCall.progressElapsedMs === undefined || !toolCall.progressPhase) return null;
    const seconds = Math.floor(toolCall.progressElapsedMs / 1000);
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    const elapsed = mins > 0 ? `${mins}m${secs}s` : `${secs}s`;
    const phaseLabel = {
        EXECUTING:         'running',
        AWAITING_APPROVAL: 'awaiting approval',
        STREAMING:         'streaming',
    }[toolCall.progressPhase];
    return (
        <span
            className="text-[10px] px-1.5 py-0.5 rounded border font-mono bg-blue-500/10 text-blue-400 border-blue-500/20 animate-pulse"
            title={`${phaseLabel} · ${elapsed}`}
        >
            {phaseLabel} · {elapsed}
        </span>
    );
}

function ToolRiskBadge({ toolName }: { toolName: string }) {
    const level = getToolRiskLevel(toolName);
    const config = {
        read:    { label: 'read',    cls: 'bg-blue-500/10 text-blue-400 border-blue-500/20' },
        write:   { label: 'write',   cls: 'bg-amber-500/10 text-amber-400 border-amber-500/20' },
        execute: { label: 'execute', cls: 'bg-red-500/10 text-red-400 border-red-500/20' },
        other:   { label: 'tool',    cls: 'bg-[var(--bg-secondary)] text-[var(--text-muted)] border-[var(--border)]' },
    }[level];

    return (
        <span className={`text-[10px] px-1.5 py-0.5 rounded border font-mono ${config.cls}`}>
            {config.label}
        </span>
    );
}

interface ToolCallCardProps {
    toolCall: ToolCall;
    onApprove?: (
        toolCallId: string,
        approved: boolean,
        reason?: string,
        editedArgs?: Record<string, unknown>,
    ) => void;
    /** Timeout in seconds before auto-reject. Default: 120. */
    approvalTimeout?: number;
}

interface ResultOutputProps {
    toolName: string;
    result: string;
    input: Record<string, unknown>;
}

function ResultOutput({ toolName, result, input }: ResultOutputProps) {
    const [resultExpanded, setResultExpanded] = useState(false);
    const { lines: visibleLines, hiddenCount } = collapseLines(result, resultExpanded);
    const totalLines = result.split('\n').length;
    const isLongOutput = totalLines > COLLAPSE_LINES;

    const isFileWriteTool = FILE_WRITE_TOOLS.has(toolName);
    const containsDiff = hasUnifiedDiff(result);
    const filePath = containsDiff
        ? extractDiffFilePath(result)
        : (input as { path?: string; file?: string })?.path ?? (input as { path?: string; file?: string })?.file ?? '';

    // write_file: render diff view with empty original
    if (toolName === 'write_file' && containsDiff) {
        return (
            <div className="border-t border-[var(--border)]">
                <div className="px-3 py-2 text-xs font-mono text-[var(--text-secondary)] whitespace-pre-wrap break-all bg-[var(--code-bg)]">
                    {visibleLines.join('\n')}
                    {hiddenCount > 0 && !resultExpanded && (
                        <span className="block text-[var(--accent)]">
                            ... and {hiddenCount} more line{hiddenCount > 1 ? 's' : ''}
                        </span>
                    )}
                </div>
                {isLongOutput && (
                    <button
                        onClick={() => setResultExpanded((prev) => !prev)}
                        className="w-full px-3 py-1 text-[10px] text-[var(--accent)] hover:underline text-left border-t border-[var(--border)]"
                    >
                        {resultExpanded ? '▴ Collapse' : `▾ Show ${hiddenCount} more lines`}
                    </button>
                )}
                <div className="px-3 py-2">
                    <FileDiffView
                        fileName={filePath}
                        original=""
                        modified={result}
                        mode="preview"
                    />
                </div>
            </div>
        );
    }

    // Other file-write tools: render inline diff if result contains unified diff
    if (isFileWriteTool && containsDiff) {
        return (
            <div className="border-t border-[var(--border)]">
                <div className="px-3 py-2 text-xs font-mono text-[var(--text-secondary)] whitespace-pre-wrap break-all bg-[var(--code-bg)]">
                    {visibleLines.join('\n')}
                    {hiddenCount > 0 && !resultExpanded && (
                        <span className="block text-[var(--accent)]">
                            ... and {hiddenCount} more line{hiddenCount > 1 ? 's' : ''}
                        </span>
                    )}
                </div>
                {isLongOutput && (
                    <button
                        onClick={() => setResultExpanded((prev) => !prev)}
                        className="w-full px-3 py-1 text-[10px] text-[var(--accent)] hover:underline text-left border-t border-[var(--border)]"
                    >
                        {resultExpanded ? '▴ Collapse' : `▾ Show ${hiddenCount} more lines`}
                    </button>
                )}
                <div className="px-3 py-2">
                    <FileDiffView
                        fileName={filePath}
                        original={extractOriginal(result)}
                        modified={extractModified(result)}
                    />
                </div>
            </div>
        );
    }

    // File-write tool without diff content but with path in input: show file path badge
    if (isFileWriteTool && filePath) {
        return (
            <div className="border-t border-[var(--border)]">
                <div className="px-3 py-2 text-xs font-mono text-[var(--text-secondary)] whitespace-pre-wrap break-all bg-[var(--code-bg)]">
                    {visibleLines.join('\n')}
                    {hiddenCount > 0 && !resultExpanded && (
                        <span className="block text-[var(--accent)]">
                            ... and {hiddenCount} more line{hiddenCount > 1 ? 's' : ''}
                        </span>
                    )}
                </div>
                {isLongOutput && (
                    <button
                        onClick={() => setResultExpanded((prev) => !prev)}
                        className="w-full px-3 py-1 text-[10px] text-[var(--accent)] hover:underline text-left border-t border-[var(--border)]"
                    >
                        {resultExpanded ? '▴ Collapse' : `▾ Show ${hiddenCount} more lines`}
                    </button>
                )}
                <div className="px-3 py-1.5 flex items-center gap-1.5">
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-[var(--bg-tertiary)] text-[var(--text-muted)] font-mono border border-[var(--border)]">
                        {filePath}
                    </span>
                </div>
            </div>
        );
    }

    // bash tool: use TerminalOutput
    if (toolName === 'bash') {
        return (
            <div className="border-t border-[var(--border)]">
                <div className="relative">
                    <TerminalOutput output={resultExpanded ? result : visibleLines.join('\n')} />
                    {isLongOutput && !resultExpanded && (
                        <div className="absolute bottom-0 left-0 right-0 h-8 bg-gradient-to-t from-[var(--code-bg)] to-transparent" />
                    )}
                </div>
                {isLongOutput && (
                    <button
                        onClick={() => setResultExpanded((prev) => !prev)}
                        className="w-full px-3 py-1 text-[10px] text-[var(--accent)] hover:underline text-left border-t border-[var(--border)]"
                    >
                        {resultExpanded ? '▴ Collapse' : `▾ Show ${hiddenCount} more lines`}
                    </button>
                )}
            </div>
        );
    }

    // Generic tool: show raw output with line-based expand/collapse
    return (
        <div className="border-t border-[var(--border)]">
            <pre className="px-3 py-2 text-xs font-mono text-[var(--text-secondary)] overflow-x-auto bg-[var(--code-bg)] whitespace-pre-wrap break-all">
                {visibleLines.join('\n')}
                {hiddenCount > 0 && !resultExpanded && (
                    <span className="block text-[var(--accent)]">
                        ... and {hiddenCount} more line{hiddenCount > 1 ? 's' : ''}
                    </span>
                )}
            </pre>
            {isLongOutput && (
                <button
                    onClick={() => setResultExpanded((prev) => !prev)}
                    className="w-full px-3 py-1 text-[10px] text-[var(--accent)] hover:underline text-left border-t border-[var(--border)]"
                >
                    {resultExpanded ? '▴ Collapse' : `▾ Show ${hiddenCount} more lines`}
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
        label: 'Running',
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

/**
 * Status indicator dot classes based on tool state.
 * Maps to: executing/pending, completed, error, requires_approval.
 */
function statusDotClasses(tc: ToolCall): string {
    if (tc.requiresApproval && tc.status === 'pending') {
        return 'bg-blue-500 animate-pulse';
    }
    switch (tc.status) {
        case 'pending':
        case 'approved':
            return 'bg-amber-400 animate-pulse';
        case 'done':
            return 'bg-green-500';
        case 'error':
            return 'bg-red-500';
        case 'rejected':
            return 'bg-red-500';
    }
}

export function ToolCallCard({ toolCall, onApprove, approvalTimeout = 120 }: ToolCallCardProps) {
    const config = statusConfig[toolCall.status];
    const risk = getToolRisk(toolCall.toolName);
    const riskLabel = RISK_LABELS[risk];
    const [expanded, setExpanded] = useState(false);

    // Card-level collapse: completed cards start collapsed, showing only a preview.
    // Pending/running cards are always expanded so the user sees live state.
    const isComplete = toolCall.status === 'done' || toolCall.status === 'error' || toolCall.status === 'rejected';
    const [cardCollapsed, setCardCollapsed] = useState(isComplete);
    // Auto-collapse once the tool finishes executing
    useEffect(() => {
        if (isComplete) setCardCollapsed(true);
    }, [isComplete]);

    const collapsedPreview = useMemo(
        () => getCollapsedPreview(toolCall.toolName, toolCall.input, toolCall.result),
        [toolCall.toolName, toolCall.input, toolCall.result],
    );

    // Timeout countdown for pending tool calls
    const [timeRemaining, setTimeRemaining] = useState(approvalTimeout);
    const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
    const isPending = toolCall.requiresApproval && toolCall.status === 'pending';

    const fileWriteInfo = toolCall.status === 'pending'
        ? extractFileWriteInfo(toolCall.toolName, toolCall.input ?? {})
        : null;

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

    // Keyboard shortcuts: y = approve, n = reject. Suppressed for exit_plan_mode because
    // that card has inline-editable inputs/textareas — typing 'y' inside an item should
    // type 'y', not approve the whole plan. Exit-plan card handles its own buttons.
    const handleKeyDown = useCallback(
        (e: KeyboardEvent) => {
            if (!isPending || !onApprove) return;
            if (toolCall.toolName === 'exit_plan_mode') return;
            const target = e.target as HTMLElement | null;
            if (
                target &&
                (target.tagName === 'INPUT' ||
                    target.tagName === 'TEXTAREA' ||
                    target.isContentEditable)
            ) {
                return;
            }
            if (e.key === 'y' || e.key === 'Y') {
                e.preventDefault();
                onApprove(toolCall.id, true);
            } else if (e.key === 'n' || e.key === 'N') {
                e.preventDefault();
                onApprove(toolCall.id, false);
            }
        },
        [isPending, onApprove, toolCall.id, toolCall.toolName],
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

    // Plan-mode approval: structured items list with inline editing + reject feedback.
    // Backed by ExitPlanModeTool's {overview, items: [{content, priority?}]} schema.
    // The card surfaces items as an editable checklist; on approve we send the user's
    // current edits via editedArgs.items, on reject we send their feedback as `reason`.
    if (toolCall.toolName === 'exit_plan_mode') {
        return (
            <ExitPlanModeCard
                toolCall={toolCall}
                onApprove={onApprove}
                isPending={isPending}
                timeRemaining={timeRemaining}
                formatTime={formatTime}
                progressPct={progressPct}
            />
        );
    }

    return (
        <div className={`my-2 border ${RISK_COLORS[risk]} rounded-lg overflow-hidden bg-[var(--bg-secondary)]`}>
            <div
                className={`px-3 py-2 flex items-center justify-between ${isComplete ? 'cursor-pointer hover:bg-[var(--bg-hover)] transition-colors' : ''}`}
                onClick={() => { if (isComplete) setCardCollapsed(prev => !prev); }}
            >
                <div className="flex items-center gap-2 min-w-0">
                    <span
                        className={`w-1 h-1 rounded-full flex-shrink-0 ${statusDotClasses(toolCall)}`}
                        title={toolCall.status}
                    />
                    <code className="text-sm font-mono font-medium text-[var(--text-primary)] shrink-0">
                        {toolCall.toolName}
                    </code>
                    <ToolRiskBadge toolName={toolCall.toolName} />
                    {riskLabel && toolCall.status === 'pending' && (
                        <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${RISK_BADGE_COLORS[risk]}`}>
                            {riskLabel}
                        </span>
                    )}
                    <span className={`flex items-center gap-1 text-xs ${config.color} shrink-0`}>
                        {config.icon}
                        {config.label}
                    </span>
                    {/* Collapsed preview: brief result summary */}
                    {cardCollapsed && isComplete && collapsedPreview && (
                        <span className="text-xs text-[var(--text-muted)] truncate min-w-0" title={collapsedPreview}>
                            {collapsedPreview}
                        </span>
                    )}
                </div>
                <div className="flex items-center gap-2 shrink-0">
                    <FailureReasonChip toolCall={toolCall} />
                    <ProgressChip toolCall={toolCall} />
                    {toolCall.durationMs !== undefined && toolCall.status === 'done' && (
                        <span className="text-xs text-[var(--text-muted)]">
                            {(toolCall.durationMs / 1000).toFixed(1)}s
                        </span>
                    )}
                    {isComplete && (
                        <span className="text-[var(--text-muted)]">
                            {cardCollapsed ? <ChevronDown size={12} /> : <ChevronUp size={12} />}
                        </span>
                    )}
                </div>
            </div>

            {!cardCollapsed && (
                <>
                    <button
                        onClick={() => setExpanded((prev) => !prev)}
                        className="w-full px-3 py-1.5 text-left text-xs text-[var(--text-secondary)] hover:bg-[var(--bg-hover)] transition-colors border-t border-[var(--border)]"
                    >
                        {expanded ? 'Hide' : 'Show'} input
                    </button>

                    {expanded && (
                        <pre className="px-3 py-2 text-xs font-mono text-[var(--text-secondary)] overflow-x-auto bg-[var(--code-bg)] max-h-48">
                            {JSON.stringify(stripStreamingMarker(toolCall.input), null, 2)}
                        </pre>
                    )}

                    {toolCall.toolName === 'todo_write' ? (
                        <div className="px-3 pb-2 border-t border-[var(--border)] pt-2">
                            {(() => {
                                const { todos, overview } = parseTodoWriteInput(toolCall.input);
                                return <InlineTodoCard todos={todos} overview={overview} />;
                            })()}
                        </div>
                    ) : (
                        toolCall.result && toolCall.status === 'done' && (
                            <ResultOutput
                                toolName={toolCall.toolName}
                                result={toolCall.result}
                                input={toolCall.input}
                            />
                        )
                    )}

                    {fileWriteInfo && <FileContentPreview info={fileWriteInfo} />}
                </>
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
                    <div className="mt-1.5 flex items-center gap-1.5 text-[10px] text-[var(--text-muted)]">
                        <kbd className="px-1 py-0.5 rounded border border-[var(--border)] bg-[var(--bg-tertiary)] font-mono">y</kbd>
                        <span>approve</span>
                        <span className="opacity-40">·</span>
                        <kbd className="px-1 py-0.5 rounded border border-[var(--border)] bg-[var(--bg-tertiary)] font-mono">n</kbd>
                        <span>reject</span>
                    </div>
                </div>
            )}
        </div>
    );
}

interface ExitPlanModeCardProps {
    toolCall: ToolCall;
    onApprove?: (
        toolCallId: string,
        approved: boolean,
        reason?: string,
        editedArgs?: Record<string, unknown>,
    ) => void;
    isPending: boolean;
    timeRemaining: number;
    formatTime: (s: number) => string;
    progressPct: number;
}

interface EditableItem extends PlanItem {
    /** local-only key; backend doesn't read this */
    key: string;
    /** when false, item is excluded from approval payload */
    enabled: boolean;
}

const PRIORITY_OPTIONS: { value: PlanItem['priority']; label: string }[] = [
    { value: undefined, label: '—' },
    { value: 'high', label: 'High' },
    { value: 'medium', label: 'Medium' },
    { value: 'low', label: 'Low' },
];

const PRIORITY_BADGE: Record<NonNullable<PlanItem['priority']>, string> = {
    high: 'bg-rose-500/15 text-rose-300 border-rose-500/30',
    medium: 'bg-amber-500/15 text-amber-300 border-amber-500/30',
    low: 'bg-slate-500/15 text-slate-300 border-slate-500/30',
};

function ExitPlanModeCard({
    toolCall,
    onApprove,
    isPending,
    timeRemaining,
    formatTime,
    progressPct,
}: ExitPlanModeCardProps) {
    const input = (toolCall.input ?? {}) as Record<string, unknown>;
    const overview = typeof input.overview === 'string' ? input.overview : '';
    const planContent = typeof input.plan_content === 'string' ? input.plan_content : '';
    const initialItems = useMemo(() => parseExitPlanItems(input), [toolCall.input]);

    const [items, setItems] = useState<EditableItem[]>(() =>
        initialItems.map((it, idx) => ({
            ...it,
            key: `init-${idx}`,
            enabled: true,
        })),
    );
    const [rejecting, setRejecting] = useState(false);
    const [rejectReason, setRejectReason] = useState('');
    // Plan body collapse: default expanded while pending so the user reads the rationale
    // before approving; collapsed once a decision is recorded so the chat doesn't get
    // dominated by stale plan markdown.
    const [planBodyExpanded, setPlanBodyExpanded] = useState(isPending);
    useEffect(() => {
        if (!isPending) setPlanBodyExpanded(false);
    }, [isPending]);

    // Result text decides post-approval state. Backend echoes "Exited Plan Mode..."
    // on approve and "Plan exit denied: <reason>\nStill in Plan Mode" on reject.
    const result = toolCall.result ?? '';
    const isApproved =
        !isPending && /Exited Plan Mode|Write tools are now available/i.test(result);
    const isRejected =
        !isPending && /Plan exit denied|Still in Plan Mode|Keep researching/i.test(result);
    const isErrored = !isPending && toolCall.isError;
    const headerLabel = isPending
        ? 'Plan ready for review'
        : isApproved
            ? 'Plan approved'
            : isRejected
                ? 'Plan rejected — keep researching'
                : isErrored
                    ? 'Plan submission failed'
                    : 'Plan submitted';
    const headerColor = isPending
        ? 'text-purple-300'
        : isApproved
            ? 'text-emerald-300'
            : isRejected
                ? 'text-amber-300'
                : isErrored
                    ? 'text-rose-300'
                    : 'text-purple-300';
    const borderColor = isPending
        ? 'border-purple-500/40'
        : isApproved
            ? 'border-emerald-500/30'
            : isRejected
                ? 'border-amber-500/30'
                : 'border-[var(--border)]';
    const bgTint = isPending
        ? 'from-purple-500/5'
        : isApproved
            ? 'from-emerald-500/5'
            : isRejected
                ? 'from-amber-500/5'
                : 'from-transparent';
    const headerBg = isPending
        ? 'bg-purple-500/10 border-b border-purple-500/30'
        : isApproved
            ? 'bg-emerald-500/10 border-b border-emerald-500/30'
            : isRejected
                ? 'bg-amber-500/10 border-b border-amber-500/30'
                : 'bg-[var(--bg-secondary)] border-b border-[var(--border)]';
    const HeaderIcon = isApproved ? Check : isRejected ? X : ClipboardList;

    const updateItem = (key: string, patch: Partial<EditableItem>) => {
        setItems((prev) => prev.map((it) => (it.key === key ? { ...it, ...patch } : it)));
    };
    const removeItem = (key: string) => {
        setItems((prev) => prev.filter((it) => it.key !== key));
    };
    const addItem = () => {
        setItems((prev) => [
            ...prev,
            { key: `new-${Date.now()}-${prev.length}`, content: '', enabled: true },
        ]);
    };

    const enabledItems = items.filter((it) => it.enabled && it.content.trim());
    const canApprove = isPending && !!onApprove && enabledItems.length > 0;

    const handleApprove = () => {
        if (!onApprove || !canApprove) return;
        const payload = enabledItems.map((it) => ({
            content: it.content.trim(),
            ...(it.priority ? { priority: it.priority } : {}),
        }));
        // Items roundtrip as a JSON string because the backend ToolParam type is String.
        // Sending a stringified array sidesteps Jackson's coercion of a list-shaped patch.
        onApprove(toolCall.id, true, undefined, { items: JSON.stringify(payload) });
    };

    const handleConfirmReject = () => {
        if (!onApprove) return;
        const reason = rejectReason.trim() || 'User rejected the plan';
        onApprove(toolCall.id, false, reason);
    };

    return (
        <div
            className={`my-2 border-2 ${borderColor} rounded-lg overflow-hidden bg-gradient-to-br ${bgTint} to-transparent`}
        >
            <div className={`px-3 py-2 flex items-center gap-2 ${headerBg}`}>
                <HeaderIcon size={14} className={`${headerColor} shrink-0`} />
                <span className={`text-sm font-semibold ${headerColor}`}>{headerLabel}</span>
                <span className="text-[11px] text-[var(--text-muted)] ml-2">
                    {enabledItems.length}/{items.length} item{items.length === 1 ? '' : 's'}
                </span>
                {isPending && (
                    <span className="ml-auto text-[10px] font-mono text-[var(--text-muted)]">
                        {formatTime(timeRemaining)}
                    </span>
                )}
            </div>

            {overview && (
                <div className="px-3 py-2 text-xs text-[var(--text-secondary)] italic border-b border-[var(--border)]">
                    {overview}
                </div>
            )}

            {planContent && (
                <div className="border-b border-[var(--border)] bg-[var(--bg-secondary)]/40">
                    <button
                        onClick={() => setPlanBodyExpanded((p) => !p)}
                        className="w-full px-3 py-1.5 flex items-center gap-1.5 text-[11px] font-medium text-[var(--text-secondary)] hover:bg-[var(--bg-hover)] transition-colors"
                    >
                        {planBodyExpanded ? (
                            <ChevronUp size={12} className="text-[var(--text-muted)]" />
                        ) : (
                            <ChevronDown size={12} className="text-[var(--text-muted)]" />
                        )}
                        <span>Plan</span>
                        <span className="text-[var(--text-muted)]">·</span>
                        <span className="text-[var(--text-muted)] font-normal">
                            {planBodyExpanded ? 'hide rationale' : 'show full rationale'}
                        </span>
                    </button>
                    {planBodyExpanded && (
                        <div className="px-3 py-2 max-h-[60vh] overflow-y-auto bg-[var(--bg-primary)] border-t border-[var(--border)]">
                            <div className="prose prose-sm dark:prose-invert max-w-none text-[var(--text-primary)] text-[13px] leading-relaxed">
                                <LazyMarkdown>{planContent}</LazyMarkdown>
                            </div>
                        </div>
                    )}
                </div>
            )}

            <div className="px-3 py-2 space-y-1.5 bg-[var(--bg-primary)]">
                {items.length === 0 && (
                    <div className="text-xs text-[var(--text-muted)] italic py-2">
                        No items in this plan.
                    </div>
                )}
                {items.map((it, idx) => (
                    <PlanItemRow
                        key={it.key}
                        index={idx + 1}
                        item={it}
                        editable={isPending}
                        onChange={(patch) => updateItem(it.key, patch)}
                        onRemove={() => removeItem(it.key)}
                    />
                ))}
                {isPending && (
                    <button
                        onClick={addItem}
                        className="flex items-center gap-1 text-[11px] text-[var(--text-muted)] hover:text-[var(--accent)] mt-1 transition-colors"
                    >
                        <Plus size={11} /> Add item
                    </button>
                )}
            </div>

            {isPending && rejecting && (
                <div className="px-3 py-2 border-t border-[var(--border)] bg-[var(--bg-secondary)] space-y-2">
                    <label className="block text-[11px] font-medium text-[var(--text-secondary)]">
                        What should the agent change? (sent back so the agent can refine the plan)
                    </label>
                    <textarea
                        value={rejectReason}
                        onChange={(e) => setRejectReason(e.target.value)}
                        rows={3}
                        autoFocus
                        placeholder="e.g. split step 2 into smaller tasks, drop the migration step…"
                        className="w-full text-xs px-2 py-1.5 rounded border border-[var(--border)] bg-[var(--bg-primary)] text-[var(--text-primary)] placeholder:text-[var(--text-muted)] focus:outline-none focus:border-[var(--accent)] resize-y"
                    />
                    <div className="flex items-center gap-2">
                        <button
                            onClick={handleConfirmReject}
                            className="flex items-center gap-1 px-3 py-1.5 text-xs font-medium text-white bg-[var(--color-danger)] hover:bg-[var(--color-danger)]/90 rounded transition-colors"
                        >
                            <X size={13} />
                            Send feedback & keep researching
                        </button>
                        <button
                            onClick={() => {
                                setRejecting(false);
                                setRejectReason('');
                            }}
                            className="text-[11px] text-[var(--text-muted)] hover:text-[var(--text-primary)] px-2 py-1"
                        >
                            Cancel
                        </button>
                    </div>
                </div>
            )}

            {isPending && !rejecting && onApprove && (
                <div className="px-3 py-2 flex items-center gap-2 border-t border-[var(--border)]">
                    <button
                        onClick={handleApprove}
                        disabled={!canApprove}
                        className="flex items-center gap-1 px-3 py-1.5 text-xs font-medium text-white rounded bg-purple-600 hover:bg-purple-700 disabled:bg-purple-600/40 disabled:cursor-not-allowed transition-colors"
                    >
                        <Check size={13} />
                        Approve & exit plan mode
                    </button>
                    <button
                        onClick={() => setRejecting(true)}
                        className="flex items-center gap-1 px-3 py-1.5 text-xs font-medium text-[var(--text-primary)] bg-[var(--bg-tertiary)] hover:bg-[var(--bg-hover)] border border-[var(--border)] rounded transition-colors"
                    >
                        <X size={13} />
                        Reject with feedback
                    </button>
                    <div className="ml-auto text-[10px] text-[var(--text-muted)]">
                        edit items inline · reject sends a refinement note
                    </div>
                </div>
            )}

            {isPending && (
                <div className="h-1 bg-[var(--bg-tertiary)] overflow-hidden">
                    <div
                        className="h-full bg-purple-500 transition-all duration-1000"
                        style={{ width: `${100 - progressPct}%` }}
                    />
                </div>
            )}

            {!isPending && isRejected && result && (
                <div className="px-3 py-2 text-[11px] text-[var(--text-muted)] border-t border-[var(--border)] bg-[var(--bg-secondary)] whitespace-pre-wrap font-mono">
                    {result}
                </div>
            )}
        </div>
    );
}

interface PlanItemRowProps {
    index: number;
    item: EditableItem;
    editable: boolean;
    onChange: (patch: Partial<EditableItem>) => void;
    onRemove: () => void;
}

function PlanItemRow({ index, item, editable, onChange, onRemove }: PlanItemRowProps) {
    const dimmed = !item.enabled;
    const taRef = useRef<HTMLTextAreaElement | null>(null);
    // Auto-grow the textarea so multi-line plan items render without clipping. Resetting
    // height to 'auto' first lets shrinking work when content gets shorter; the layout
    // effect runs synchronously so the row never flashes at the wrong height.
    useEffect(() => {
        const el = taRef.current;
        if (!el) return;
        el.style.height = 'auto';
        el.style.height = `${el.scrollHeight}px`;
    }, [item.content, editable]);
    return (
        <div
            className={`flex items-start gap-2 px-2 py-1.5 rounded border ${
                item.enabled
                    ? 'border-transparent hover:border-[var(--border)] hover:bg-[var(--bg-secondary)]/40'
                    : 'border-dashed border-[var(--border)] opacity-60'
            } transition-colors`}
        >
            <input
                type="checkbox"
                checked={item.enabled}
                disabled={!editable}
                onChange={(e) => onChange({ enabled: e.target.checked })}
                className="mt-1 cursor-pointer accent-purple-500 disabled:cursor-default"
                title={item.enabled ? 'Include this item' : 'Skip this item'}
            />
            <span className="text-[11px] font-mono text-[var(--text-muted)] mt-1 w-4 shrink-0">
                {index}.
            </span>
            <div className="flex-1 min-w-0">
                {editable ? (
                    <textarea
                        ref={taRef}
                        value={item.content}
                        onChange={(e) => onChange({ content: e.target.value })}
                        rows={1}
                        placeholder="Plan item content"
                        className={`w-full text-[13px] leading-snug px-2 py-1.5 rounded border bg-[var(--bg-primary)] text-[var(--text-primary)] focus:outline-none resize-none overflow-hidden ${
                            dimmed
                                ? 'border-transparent line-through'
                                : 'border-[var(--border)] focus:border-[var(--accent)]'
                        }`}
                    />
                ) : (
                    <div
                        className={`text-xs whitespace-pre-wrap ${
                            dimmed
                                ? 'text-[var(--text-muted)] line-through'
                                : 'text-[var(--text-primary)]'
                        }`}
                    >
                        {item.content || (
                            <span className="italic text-[var(--text-muted)]">empty</span>
                        )}
                    </div>
                )}
            </div>
            {editable ? (
                <select
                    value={item.priority ?? ''}
                    onChange={(e) =>
                        onChange({
                            priority:
                                (e.target.value as PlanItem['priority']) || undefined,
                        })
                    }
                    className="text-[11px] px-1.5 py-1 rounded border border-[var(--border)] bg-[var(--bg-primary)] text-[var(--text-primary)] cursor-pointer focus:outline-none focus:border-[var(--accent)]"
                    title="Priority"
                >
                    {PRIORITY_OPTIONS.map((opt) => (
                        <option key={opt.label} value={opt.value ?? ''}>
                            {opt.label}
                        </option>
                    ))}
                </select>
            ) : (
                item.priority && (
                    <span
                        className={`text-[10px] px-1.5 py-0.5 rounded border ${PRIORITY_BADGE[item.priority]}`}
                    >
                        {item.priority}
                    </span>
                )
            )}
            {editable && (
                <button
                    onClick={onRemove}
                    title="Remove item"
                    className="text-[var(--text-muted)] hover:text-rose-400 transition-colors mt-1"
                >
                    <Trash2 size={12} />
                </button>
            )}
        </div>
    );
}
