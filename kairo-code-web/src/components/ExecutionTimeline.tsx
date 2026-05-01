import { useEffect, useState } from 'react';
import { Activity, X, RefreshCw, Clock, CheckCircle, XCircle } from 'lucide-react';

interface TraceEntry {
    phase: string;
    toolName: string;
    durationMs: number;
    status: string;
    ts: string;
}

interface ExecutionTimelineProps {
    sessionId: string | null;
    onClose: () => void;
}

const TOOL_COLORS: Record<string, string> = {
    bash_execute: 'bg-orange-500',
    read_file: 'bg-blue-500',
    write_file: 'bg-emerald-500',
    edit_file: 'bg-amber-500',
    grep: 'bg-purple-500',
    glob: 'bg-cyan-500',
    default: 'bg-[var(--accent)]',
};

export function ExecutionTimeline({ sessionId, onClose }: ExecutionTimelineProps) {
    const [entries, setEntries] = useState<TraceEntry[]>([]);
    const [loading, setLoading] = useState(false);
    const [selected, setSelected] = useState<TraceEntry | null>(null);

    const refresh = async () => {
        if (!sessionId) return;
        setLoading(true);
        try {
            const res = await fetch(`/api/trace/${sessionId}`);
            if (res.ok) setEntries(await res.json());
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => { refresh(); }, [sessionId]);

    const maxDuration = Math.max(1, ...entries.map(e => e.durationMs));
    const totalDuration = entries.reduce((s, e) => s + e.durationMs, 0);

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm" onClick={onClose}>
            <div
                className="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl shadow-2xl w-full max-w-3xl max-h-[80vh] flex flex-col overflow-hidden"
                onClick={e => e.stopPropagation()}
            >
                <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--border)]">
                    <div className="flex items-center gap-2">
                        <Activity size={14} className="text-[var(--accent)]" />
                        <span className="text-sm font-semibold text-[var(--text-primary)]">Execution Timeline</span>
                        {entries.length > 0 && (
                            <span className="text-xs text-[var(--text-muted)]">
                                {entries.length} calls · {(totalDuration / 1000).toFixed(1)}s total
                            </span>
                        )}
                    </div>
                    <div className="flex items-center gap-1">
                        <button onClick={refresh} disabled={loading}
                            className="p-1 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)]">
                            <RefreshCw size={12} className={loading ? 'animate-spin' : ''} />
                        </button>
                        <button onClick={onClose}
                            className="p-1 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] hover:text-red-400">
                            <X size={13} />
                        </button>
                    </div>
                </div>

                <div className="flex-1 overflow-y-auto p-4">
                    {entries.length === 0 ? (
                        <div className="flex flex-col items-center py-12 text-[var(--text-muted)]">
                            <Activity size={32} className="opacity-30 mb-2" />
                            <p className="text-sm">{loading ? 'Loading…' : 'No trace data yet.'}</p>
                        </div>
                    ) : (
                        <div className="space-y-1.5">
                            {entries.map((entry, i) => {
                                const color = TOOL_COLORS[entry.toolName] ?? TOOL_COLORS.default;
                                const widthPct = Math.max(2, (entry.durationMs / maxDuration) * 100);
                                const isOk = entry.status === 'ok' || entry.status === 'success';
                                return (
                                    <div
                                        key={i}
                                        className="flex items-center gap-2 cursor-pointer hover:bg-[var(--bg-hover)] rounded px-2 py-1"
                                        onClick={() => setSelected(selected?.ts === entry.ts ? null : entry)}
                                    >
                                        <span className="text-xs text-[var(--text-muted)] w-5 text-right shrink-0">{i + 1}</span>
                                        {isOk
                                            ? <CheckCircle size={10} className="text-emerald-400 shrink-0" />
                                            : <XCircle size={10} className="text-red-400 shrink-0" />
                                        }
                                        <span className="text-xs font-mono text-[var(--text-primary)] w-40 truncate shrink-0">{entry.toolName}</span>
                                        <div className="flex-1 h-4 bg-[var(--bg-hover)] rounded overflow-hidden">
                                            <div className={`h-full ${color} rounded opacity-80`} style={{ width: `${widthPct}%` }} />
                                        </div>
                                        <span className="text-xs text-[var(--text-muted)] w-16 text-right shrink-0 flex items-center gap-0.5">
                                            <Clock size={9} />{entry.durationMs}ms
                                        </span>
                                    </div>
                                );
                            })}
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
}
