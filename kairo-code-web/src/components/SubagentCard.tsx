import { useState } from 'react';
import { Bot, Check, X, Loader2, ChevronDown, ChevronRight, Square, GitMerge, Trash2, FolderOpen } from 'lucide-react';
import type { ToolCall, SubagentEvent } from '@/types/agent';

interface SubagentCardProps {
    toolCall: ToolCall;
    onStop?: () => void;
}

function formatDuration(ms: number): string {
    if (ms < 1000) return `${ms}ms`;
    const s = Math.round(ms / 1000);
    if (s < 60) return `${s}s`;
    const m = Math.floor(s / 60);
    return `${m}m${s % 60}s`;
}

function buildToolSummary(events: SubagentEvent[]): string {
    const counts: Record<string, number> = {};
    for (const e of events) {
        if (e.childEventType === 'TOOL_RESULT' && e.childToolName) {
            counts[e.childToolName] = (counts[e.childToolName] || 0) + 1;
        }
    }
    return Object.entries(counts)
        .map(([name, count]) => `${name} ${count}`)
        .join(' · ');
}

function OutcomeIcon({ outcome }: { outcome: string }) {
    switch (outcome) {
        case 'merge': return <GitMerge size={12} className="text-emerald-400" />;
        case 'discard': return <Trash2 size={12} className="text-red-400" />;
        case 'keep': return <FolderOpen size={12} className="text-blue-400" />;
        default: return null;
    }
}

export function SubagentCard({ toolCall, onStop }: SubagentCardProps) {
    const [expanded, setExpanded] = useState(true);

    const description = (toolCall.input?.description as string) || (toolCall.input?.prompt as string)?.slice(0, 80) || 'Subtask';
    const isRunning = toolCall.status === 'approved' || toolCall.status === 'pending';
    const isDone = toolCall.status === 'done';
    const isError = toolCall.status === 'error' || toolCall.isError;
    const events = toolCall.subagentEvents ?? [];
    const meta = toolCall.resultMetadata ?? {};

    const outcome = meta['task.outcome'] as string | undefined;
    const filesChanged = meta['task.files_changed'] as number | undefined;
    const insertions = meta['task.insertions'] as number | undefined;
    const deletions = meta['task.deletions'] as number | undefined;

    const toolSummary = buildToolSummary(events);
    const elapsed = toolCall.progressElapsedMs
        ? formatDuration(toolCall.progressElapsedMs)
        : toolCall.durationMs
            ? formatDuration(toolCall.durationMs)
            : toolCall.createdAt
                ? formatDuration(Date.now() - toolCall.createdAt)
                : '';

    const statusColor = isError
        ? 'border-red-500/30 bg-red-500/5'
        : isDone
            ? 'border-emerald-500/30 bg-emerald-500/5'
            : 'border-blue-500/30 bg-blue-500/5';

    return (
        <div className={`rounded-lg border ${statusColor} overflow-hidden`}>
            {/* Header */}
            <div
                className="flex items-center gap-2 px-3 py-2 cursor-pointer hover:bg-[var(--bg-hover)] transition-colors"
                onClick={() => setExpanded(!expanded)}
            >
                <button className="shrink-0 text-[var(--text-muted)]">
                    {expanded ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
                </button>

                <Bot size={14} className={isRunning ? 'text-blue-400 animate-pulse' : isDone ? 'text-emerald-400' : 'text-red-400'} />

                <span className="text-xs font-medium text-[var(--text-primary)] truncate flex-1">
                    {description}
                </span>

                {/* Status badge */}
                {isRunning && (
                    <span className="flex items-center gap-1 text-[10px] text-blue-400 bg-blue-500/10 px-1.5 py-0.5 rounded-full animate-pulse">
                        <Loader2 size={10} className="animate-spin" />
                        {elapsed}
                    </span>
                )}
                {isDone && !isError && (
                    <span className="flex items-center gap-1 text-[10px] text-emerald-400 bg-emerald-500/10 px-1.5 py-0.5 rounded-full">
                        <Check size={10} />
                        {elapsed}
                    </span>
                )}
                {isError && (
                    <span className="flex items-center gap-1 text-[10px] text-red-400 bg-red-500/10 px-1.5 py-0.5 rounded-full">
                        <X size={10} />
                        Error
                    </span>
                )}

                {/* Stop button */}
                {isRunning && onStop && (
                    <button
                        onClick={(e) => { e.stopPropagation(); onStop(); }}
                        className="p-0.5 rounded hover:bg-red-500/20 text-[var(--text-muted)] hover:text-red-400 transition-colors"
                        title="Stop subtask"
                    >
                        <Square size={11} />
                    </button>
                )}
            </div>

            {/* Body */}
            {expanded && (
                <div className="px-3 pb-2 space-y-1.5">
                    {/* Tool call activity stream */}
                    {events.length > 0 && (
                        <div className="flex flex-wrap gap-1">
                            {events.filter(e => e.childEventType === 'TOOL_RESULT').map((e, i) => (
                                <span
                                    key={i}
                                    className={`text-[10px] px-1.5 py-0.5 rounded font-mono ${
                                        e.childIsError
                                            ? 'bg-red-500/10 text-red-400'
                                            : 'bg-[var(--bg-primary)] text-[var(--text-secondary)]'
                                    }`}
                                >
                                    {e.childToolName}
                                </span>
                            ))}
                            {isRunning && events.length > 0 && events[events.length - 1].childEventType === 'TOOL_CALL' && (
                                <span className="text-[10px] px-1.5 py-0.5 rounded bg-blue-500/10 text-blue-400 animate-pulse font-mono">
                                    {events[events.length - 1].childToolName}...
                                </span>
                            )}
                        </div>
                    )}

                    {/* Running status line */}
                    {isRunning && events.length === 0 && (
                        <p className="text-[10px] text-[var(--text-muted)] italic">
                            Spawning child agent...
                        </p>
                    )}

                    {/* Tool summary when collapsed/done */}
                    {isDone && toolSummary && (
                        <p className="text-[10px] text-[var(--text-muted)]">
                            {toolSummary}
                        </p>
                    )}

                    {/* Completion metadata */}
                    {isDone && (outcome || filesChanged !== undefined) && (
                        <div className="flex items-center gap-2 text-[10px] text-[var(--text-muted)]">
                            {outcome && (
                                <span className="flex items-center gap-0.5">
                                    <OutcomeIcon outcome={outcome} />
                                    {outcome}
                                </span>
                            )}
                            {filesChanged !== undefined && filesChanged > 0 && (
                                <span>
                                    {filesChanged} file{filesChanged !== 1 ? 's' : ''}
                                    {insertions ? ` +${insertions}` : ''}
                                    {deletions ? ` -${deletions}` : ''}
                                </span>
                            )}
                        </div>
                    )}

                    {/* Result preview */}
                    {isDone && toolCall.result && (
                        <details className="text-[10px]">
                            <summary className="cursor-pointer text-[var(--text-muted)] hover:text-[var(--text-secondary)]">
                                Show result
                            </summary>
                            <pre className="mt-1 p-2 rounded bg-[var(--bg-primary)] text-[var(--text-secondary)] overflow-x-auto max-h-32 text-[10px] leading-relaxed whitespace-pre-wrap">
                                {toolCall.result.slice(0, 500)}
                                {toolCall.result.length > 500 ? '...' : ''}
                            </pre>
                        </details>
                    )}
                </div>
            )}
        </div>
    );
}
