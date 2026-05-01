import { useState, useEffect, useCallback } from 'react';
import { Wrench, TrendingUp, Clock, CheckCircle, XCircle, X } from 'lucide-react';

export interface ToolStat {
    calls: number;
    successes: number;
    totalMillis: number;
    successRate: number;
    avgMillis: number;
}

export type ToolStatsMap = Record<string, ToolStat>;

interface ToolStatsDashboardProps {
    sessionId: string | null;
    onClose: () => void;
}

function formatMs(ms: number): string {
    if (ms < 1000) return `${ms}ms`;
    const s = ms / 1000;
    if (s < 60) return `${s.toFixed(1)}s`;
    const m = Math.floor(s / 60);
    const rem = (s % 60).toFixed(0);
    return `${m}m ${rem}s`;
}

export function ToolStatsDashboard({ sessionId, onClose }: ToolStatsDashboardProps) {
    const [stats, setStats] = useState<ToolStatsMap | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [sortBy, setSortBy] = useState<'calls' | 'totalMillis' | 'successRate'>('calls');

    const refresh = useCallback(async () => {
        if (!sessionId) {
            setStats(null);
            return;
        }
        setLoading(true);
        setError(null);
        try {
            const res = await fetch(`/api/tool-stats/${sessionId}`);
            if (!res.ok) {
                if (res.status === 404) {
                    setStats(null);
                    return;
                }
                throw new Error(`HTTP ${res.status}`);
            }
            setStats(await res.json());
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed to load tool stats');
        }
        setLoading(false);
    }, [sessionId]);

    useEffect(() => { refresh(); }, [refresh]);

    useEffect(() => {
        if (!sessionId) return;
        const interval = setInterval(refresh, 3000);
        return () => clearInterval(interval);
    }, [sessionId, refresh]);

    const sortedTools = stats
        ? Object.entries(stats).sort(([, a], [, b]) => {
              if (sortBy === 'calls') return b.calls - a.calls;
              if (sortBy === 'totalMillis') return b.totalMillis - a.totalMillis;
              return a.successRate - b.successRate;
          })
        : [];

    const totals = stats
        ? Object.values(stats).reduce(
              (acc, s) => ({
                  calls: acc.calls + s.calls,
                  successes: acc.successes + s.successes,
                  totalMillis: acc.totalMillis + s.totalMillis,
              }),
              { calls: 0, successes: 0, totalMillis: 0 }
          )
        : null;

    return (
        <div className="h-full flex flex-col bg-[var(--bg-primary)]">
            {/* Header */}
            <div className="flex items-center justify-between px-3 py-2 border-b border-[var(--border)]">
                <div className="flex items-center gap-2">
                    <Wrench size={16} className="text-[var(--text-primary)]" />
                    <span className="text-sm font-medium text-[var(--text-primary)]">Tool Usage Stats</span>
                </div>
                <button
                    onClick={onClose}
                    className="text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
                    title="Close"
                >
                    <X size={16} />
                </button>
            </div>

            {/* Content */}
            <div className="flex-1 overflow-y-auto p-3">
                {loading && !stats && (
                    <div className="text-center text-[var(--text-muted)] text-sm py-8">Loading...</div>
                )}
                {error && (
                    <div className="text-center text-red-400 text-sm py-8">{error}</div>
                )}
                {!sessionId && (
                    <div className="text-center text-[var(--text-muted)] text-sm py-8">
                        No active session
                    </div>
                )}
                {stats && Object.keys(stats).length === 0 && (
                    <div className="text-center text-[var(--text-muted)] text-sm py-8">
                        No tool calls yet
                    </div>
                )}

                {sortedTools.length > 0 && (
                    <>
                        {/* Totals row */}
                        {totals && totals.calls > 0 && (
                            <div className="mb-3 p-2 rounded-lg bg-[var(--bg-secondary)] border border-[var(--border)] text-xs">
                                <div className="grid grid-cols-4 gap-2 text-center">
                                    <div>
                                        <div className="text-[var(--text-muted)]">Total Calls</div>
                                        <div className="text-lg font-bold text-[var(--text-primary)]">{totals.calls}</div>
                                    </div>
                                    <div>
                                        <div className="text-[var(--text-muted)]">Success</div>
                                        <div className="text-lg font-bold text-green-400">
                                            {totals.calls > 0 ? ((totals.successes / totals.calls) * 100).toFixed(1) : 0}%
                                        </div>
                                    </div>
                                    <div>
                                        <div className="text-[var(--text-muted)]">Total Time</div>
                                        <div className="text-lg font-bold text-[var(--text-primary)]">{formatMs(totals.totalMillis)}</div>
                                    </div>
                                    <div>
                                        <div className="text-[var(--text-muted)]">Tools</div>
                                        <div className="text-lg font-bold text-[var(--text-primary)]">{sortedTools.length}</div>
                                    </div>
                                </div>
                            </div>
                        )}

                        {/* Sort controls */}
                        <div className="flex items-center gap-1 mb-2 text-xs text-[var(--text-muted)]">
                            <TrendingUp size={11} />
                            <span>Sort:</span>
                            {(['calls', 'totalMillis', 'successRate'] as const).map((s) => (
                                <button
                                    key={s}
                                    onClick={() => setSortBy(s)}
                                    className={`px-1.5 py-0.5 rounded ${
                                        sortBy === s
                                            ? 'bg-[var(--accent)] text-white'
                                            : 'hover:bg-[var(--bg-secondary)]'
                                    }`}
                                >
                                    {s === 'calls' ? 'Calls' : s === 'totalMillis' ? 'Time' : 'Rate'}
                                </button>
                            ))}
                        </div>

                        {/* Tool list */}
                        <div className="space-y-1.5">
                            {sortedTools.map(([name, s]) => {
                                const errorCount = s.calls - s.successes;
                                return (
                                    <div
                                        key={name}
                                        className="p-2 rounded-lg bg-[var(--bg-secondary)] border border-[var(--border)] text-xs"
                                    >
                                        <div className="flex items-center justify-between mb-1">
                                            <span className="font-mono font-medium text-[var(--text-primary)]">{name}</span>
                                            <span className="font-bold text-[var(--text-primary)]">{s.calls} calls</span>
                                        </div>
                                        <div className="grid grid-cols-3 gap-2 text-[var(--text-secondary)]">
                                            <div className="flex items-center gap-1">
                                                <CheckCircle size={11} className="text-green-400" />
                                                <span>{s.successes}</span>
                                            </div>
                                            {errorCount > 0 && (
                                                <div className="flex items-center gap-1">
                                                    <XCircle size={11} className="text-red-400" />
                                                    <span>{errorCount}</span>
                                                </div>
                                            )}
                                            <div className="flex items-center gap-1">
                                                <Clock size={11} />
                                                <span>{formatMs(s.avgMillis)} avg</span>
                                            </div>
                                        </div>
                                        {/* Success rate bar */}
                                        <div className="mt-1.5">
                                            <div className="flex justify-between text-[var(--text-muted)] mb-0.5">
                                                <span>Success rate</span>
                                                <span className={s.successRate >= 90 ? 'text-green-400' : s.successRate >= 50 ? 'text-amber-400' : 'text-red-400'}>
                                                    {s.successRate.toFixed(1)}%
                                                </span>
                                            </div>
                                            <div className="h-1 rounded-full bg-[var(--bg-tertiary)] overflow-hidden">
                                                <div
                                                    className={`h-full rounded-full transition-all ${
                                                        s.successRate >= 90 ? 'bg-green-400' : s.successRate >= 50 ? 'bg-amber-400' : 'bg-red-400'
                                                    }`}
                                                    style={{ width: `${s.successRate}%` }}
                                                />
                                            </div>
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                    </>
                )}
            </div>
        </div>
    );
}
