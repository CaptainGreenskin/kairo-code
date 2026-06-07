import { useState } from 'react';
import { CheckCircle2, XCircle, ChevronDown, ChevronRight, Clock, Bot, Copy, Check } from 'lucide-react';
import { LazyMarkdown } from './LazyMarkdown';

interface TaskNotificationCardProps {
    taskId: string;
    description: string;
    status: 'completed' | 'failed';
    durationMs: number;
    result: string;
    timestamp: number;
}

function formatDuration(ms: number): string {
    if (ms < 1000) return `${ms}ms`;
    const s = Math.round(ms / 1000);
    if (s < 60) return `${s}s`;
    const m = Math.floor(s / 60);
    const rem = s % 60;
    return rem > 0 ? `${m}m ${rem}s` : `${m}m`;
}

function extractFirstMeaningfulLines(text: string, maxLines: number): string {
    const lines = text.split('\n').filter(l => l.trim().length > 0);
    return lines.slice(0, maxLines).join('\n');
}

export function TaskNotificationCard({
    taskId,
    description,
    status,
    durationMs,
    result,
    timestamp,
}: TaskNotificationCardProps) {
    const [expanded, setExpanded] = useState(false);
    const [copied, setCopied] = useState(false);

    const time = new Date(timestamp).toLocaleTimeString([], {
        hour: '2-digit',
        minute: '2-digit',
    });
    const isSuccess = status === 'completed';
    const hasResult = result && result.trim().length > 0;
    const preview = hasResult ? extractFirstMeaningfulLines(result, 3) : '';
    const hasMore = hasResult && result.trim().length > preview.length;
    const shortId = taskId.length > 6 ? taskId.slice(-6) : taskId;

    const statusColor = isSuccess
        ? 'border-emerald-500/30 bg-emerald-500/5'
        : 'border-red-500/30 bg-red-500/5';

    const handleCopy = (e: React.MouseEvent) => {
        e.stopPropagation();
        navigator.clipboard.writeText(result);
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
    };

    return (
        <div className={`rounded-lg border ${statusColor} overflow-hidden my-2 mx-auto max-w-[90%] animate-slide-up`}>
            {/* Header */}
            <div
                className="flex items-center gap-2 px-3 py-2 cursor-pointer hover:bg-[var(--bg-hover)] transition-colors"
                onClick={() => hasResult && setExpanded(!expanded)}
            >
                {hasResult && (
                    <button className="shrink-0 text-[var(--text-muted)]">
                        {expanded ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
                    </button>
                )}

                <Bot size={14} className={isSuccess ? 'text-emerald-400' : 'text-red-400'} />

                <span className="text-xs font-medium text-[var(--text-primary)] truncate flex-1">
                    {description}
                </span>

                {/* Status badge */}
                <span className={`flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded-full ${
                    isSuccess
                        ? 'text-emerald-400 bg-emerald-500/10'
                        : 'text-red-400 bg-red-500/10'
                }`}>
                    {isSuccess ? <CheckCircle2 size={10} /> : <XCircle size={10} />}
                    {isSuccess ? 'done' : 'failed'}
                </span>

                <span className="flex items-center gap-1 text-[10px] text-[var(--text-muted)]">
                    <Clock size={10} />
                    {formatDuration(durationMs)}
                </span>

                <span className="text-[10px] text-[var(--text-muted)]">{time}</span>

                <span className="font-mono text-[9px] text-[var(--text-muted)] opacity-40">
                    {shortId}
                </span>
            </div>

            {/* Preview — always visible when collapsed and has result */}
            {!expanded && hasResult && (
                <div className="px-3 pb-2 pt-0">
                    <div
                        className="text-[11px] leading-relaxed text-[var(--text-secondary)] line-clamp-3 cursor-pointer"
                        onClick={() => setExpanded(true)}
                    >
                        <LazyMarkdown>{preview}</LazyMarkdown>
                    </div>
                    {hasMore && (
                        <button
                            className="text-[10px] text-[var(--accent)] hover:underline mt-1"
                            onClick={() => setExpanded(true)}
                        >
                            Show full result
                        </button>
                    )}
                </div>
            )}

            {/* Expanded full result */}
            {expanded && hasResult && (
                <div className="px-3 pb-3 pt-0">
                    <div className="flex items-center justify-between mb-1">
                        <span className="text-[10px] text-[var(--text-muted)]">Worker result</span>
                        <button
                            onClick={handleCopy}
                            className="flex items-center gap-1 text-[10px] text-[var(--text-muted)] hover:text-[var(--text-secondary)] transition-colors"
                            title="Copy result"
                        >
                            {copied ? <Check size={10} className="text-emerald-400" /> : <Copy size={10} />}
                            {copied ? 'Copied' : 'Copy'}
                        </button>
                    </div>
                    <div
                        className="p-3 rounded-md text-xs leading-relaxed overflow-auto max-h-[400px]"
                        style={{
                            background: 'var(--bg-primary)',
                            color: 'var(--text-secondary)',
                        }}
                    >
                        <LazyMarkdown>{result}</LazyMarkdown>
                    </div>
                </div>
            )}
        </div>
    );
}
