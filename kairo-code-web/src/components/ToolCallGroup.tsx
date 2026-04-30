import { useState, useEffect } from 'react';
import { ChevronRight, Loader2 } from 'lucide-react';
import type { ToolCall } from '@/types/agent';
import { ToolCallCard } from './ToolCallCard';

interface ToolCallGroupProps {
    toolCalls: ToolCall[];
    onApprove?: (toolCallId: string, approved: boolean) => void;
    isStreaming?: boolean;
}

function getStatusDotClass(status: ToolCall['status']): string {
    if (status === 'pending' || status === 'approved') {
        return 'bg-[var(--color-warning)] animate-pulse';
    }
    if (status === 'done') {
        return 'bg-[var(--color-success)]';
    }
    return 'bg-[var(--color-danger)]';
}

function buildToolSummary(toolCalls: ToolCall[]): string {
    const counts: Record<string, number> = {};
    for (const tc of toolCalls) {
        counts[tc.toolName] = (counts[tc.toolName] || 0) + 1;
    }
    const sorted = Object.entries(counts).sort((a, b) => b[1] - a[1]);
    const top3 = sorted.slice(0, 3);
    const parts = top3.map(([name, count]) => count > 1 ? `${name} \u00d7${count}` : name);
    const remaining = sorted.length - 3;
    if (remaining > 0) {
        parts.push(`+${remaining} more`);
    }
    return parts.join(', ');
}

function getGroupIconColor(toolCalls: ToolCall[]): string {
    const hasError = toolCalls.some(tc => tc.status === 'error' || tc.status === 'rejected');
    if (hasError) return 'text-[var(--color-danger)]';
    const hasPending = toolCalls.some(tc => tc.status === 'pending' || tc.status === 'approved');
    if (hasPending) return 'text-[var(--color-warning)]';
    return 'text-[var(--color-success)]';
}

export function ToolCallGroup({ toolCalls, onApprove, isStreaming }: ToolCallGroupProps) {
    const hasPending = toolCalls.some(tc => tc.status === 'pending' || tc.status === 'approved');
    const [expanded, setExpanded] = useState(hasPending || !!isStreaming);

    // Force expand when pending tools appear or streaming starts
    useEffect(() => {
        if (hasPending || isStreaming) {
            setExpanded(true);
        }
    }, [hasPending, isStreaming]);

    const totalDuration = toolCalls
        .filter(tc => tc.durationMs !== undefined)
        .reduce((sum, tc) => sum + (tc.durationMs ?? 0), 0);

    const toolSummary = buildToolSummary(toolCalls);
    const iconColor = getGroupIconColor(toolCalls);

    const canCollapse = !hasPending && !isStreaming;

    return (
        <div className="mt-2 space-y-1">
            <button
                onClick={() => {
                    if (canCollapse) {
                        setExpanded((prev) => !prev);
                    }
                }}
                className={`w-full flex items-center gap-2 px-3 py-1.5 text-xs text-[var(--text-secondary)] hover:bg-[var(--bg-hover)] rounded-lg border border-[var(--border)] transition-colors ${
                    !canCollapse ? 'cursor-default' : 'cursor-pointer'
                }`}
            >
                {isStreaming ? (
                    <Loader2 size={12} className={`animate-spin ${iconColor}`} />
                ) : (
                    <ChevronRight
                        size={12}
                        className={`transition-transform ${expanded ? 'rotate-90' : ''} ${iconColor}`}
                    />
                )}
                <span className="font-mono font-medium text-[var(--text-primary)]">
                    {toolCalls.length} tool calls
                </span>
                <span className="text-[var(--text-muted)]">·</span>
                <span>{toolSummary}</span>
                {totalDuration > 0 && (
                    <>
                        <span className="text-[var(--text-muted)]">·</span>
                        <span>{(totalDuration / 1000).toFixed(1)}s</span>
                    </>
                )}
                <div className="ml-auto flex items-center gap-0.5">
                    {toolCalls.map((tc) => (
                        <div
                            key={tc.id}
                            className={`w-1.5 h-1.5 rounded-full ${getStatusDotClass(tc.status)}`}
                        />
                    ))}
                </div>
            </button>

            {expanded && (
                <div className="space-y-2">
                    {toolCalls.map((tc) => (
                        <ToolCallCard
                            key={tc.id}
                            toolCall={tc}
                            onApprove={onApprove}
                        />
                    ))}
                </div>
            )}
        </div>
    );
}
