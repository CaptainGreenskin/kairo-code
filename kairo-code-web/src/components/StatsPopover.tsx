import { useState } from 'react';
import { MessageSquare, Wrench, Cpu } from 'lucide-react';
import { formatTokenCount } from '@utils/tokenCount';

interface SessionStatsData {
    userMessages: number;
    assistantMessages: number;
    toolCalls: number;
    estimatedTokens: number;
}

const CONTEXT_LIMIT = 200_000;

export function StatsPopover({ stats }: { stats: SessionStatsData }) {
    const [open, setOpen] = useState(false);
    const pct = Math.min(stats.estimatedTokens / CONTEXT_LIMIT * 100, 100);
    const pctColor = pct > 80 ? 'text-red-400' : pct > 50 ? 'text-amber-400' : 'text-green-400';

    return (
        <div className="relative">
            <button
                onClick={() => setOpen(v => !v)}
                className={`text-[10px] px-1.5 py-0.5 rounded font-mono border border-[var(--border)]
                    hover:bg-[var(--bg-secondary)] transition-colors ${pctColor}`}
                title="Session stats"
            >
                ~{formatTokenCount(stats.estimatedTokens)}
                {pct > 80 && ' ⚠'}
            </button>

            {open && (
                <>
                    <div className="fixed inset-0 z-40" onClick={() => setOpen(false)} />
                    <div className="absolute right-0 top-full mt-1 z-50 w-52 rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] shadow-lg p-3 text-xs">
                        <div className="font-medium text-[var(--text-primary)] mb-2">Session Stats</div>

                        <div className="mb-3">
                            <div className="flex justify-between text-[var(--text-muted)] mb-1">
                                <span>Context used</span>
                                <span className={pctColor}>{pct.toFixed(1)}%</span>
                            </div>
                            <div className="h-1.5 rounded-full bg-[var(--bg-secondary)] overflow-hidden">
                                <div
                                    className={`h-full rounded-full transition-all ${
                                        pct > 80 ? 'bg-red-400' : pct > 50 ? 'bg-amber-400' : 'bg-green-400'
                                    }`}
                                    style={{ width: `${pct}%` }}
                                />
                            </div>
                            <div className="text-[var(--text-muted)] mt-1">
                                ~{formatTokenCount(stats.estimatedTokens)} / {formatTokenCount(CONTEXT_LIMIT)}
                            </div>
                        </div>

                        <div className="space-y-1.5 text-[var(--text-secondary)]">
                            <div className="flex items-center justify-between">
                                <div className="flex items-center gap-1.5">
                                    <MessageSquare size={11} /> You
                                </div>
                                <span>{stats.userMessages} msgs</span>
                            </div>
                            <div className="flex items-center justify-between">
                                <div className="flex items-center gap-1.5">
                                    <Cpu size={11} /> Agent
                                </div>
                                <span>{stats.assistantMessages} msgs</span>
                            </div>
                            <div className="flex items-center justify-between">
                                <div className="flex items-center gap-1.5">
                                    <Wrench size={11} /> Tool calls
                                </div>
                                <span>{stats.toolCalls}</span>
                            </div>
                        </div>
                    </div>
                </>
            )}
        </div>
    );
}
