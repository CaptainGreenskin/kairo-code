import React, { useEffect, useState } from 'react';

interface SwarmStatus {
    teamId: string;
    status: string;
    currentPhase: string;
    phaseHistory: Array<{ phase: string; completedAt: number }>;
}

interface SwarmProgressPanelProps {
    teamId: string;
    isOpen: boolean;
    onClose: () => void;
}

const PHASES = ['Research', 'Synthesis', 'Implementation', 'Verification'];

export function SwarmProgressPanel({ teamId, isOpen, onClose }: SwarmProgressPanelProps) {
    const [swarmStatus, setSwarmStatus] = useState<SwarmStatus | null>(null);

    useEffect(() => {
        if (!isOpen || !teamId) return;
        const load = () => {
            fetch(`/api/swarms/${teamId}`)
                .then(r => r.json())
                .then(setSwarmStatus)
                .catch(() => {});
        };
        load();
        const interval = setInterval(load, 2000);
        return () => clearInterval(interval);
    }, [isOpen, teamId]);

    if (!isOpen) return null;

    const completedPhases = swarmStatus?.phaseHistory.map(p => p.phase) ?? [];
    const currentPhase = swarmStatus?.currentPhase ?? '';

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
            <div className="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl
                            w-[500px] shadow-2xl">
                <div className="flex items-center justify-between px-4 py-3
                                border-b border-[var(--border)]">
                    <h2 className="text-sm font-semibold text-[var(--text-primary)]">
                        Swarm Progress
                    </h2>
                    <button onClick={onClose}
                            className="text-[var(--text-muted)] hover:text-[var(--text-primary)]
                                       text-lg leading-none"
                            title="Close">
                        &times;
                    </button>
                </div>

                <div className="p-6">
                    {/* Phase progress bar */}
                    <div className="flex items-center gap-0 mb-6">
                        {PHASES.map((phase, i) => {
                            const done = completedPhases.includes(phase);
                            const active = currentPhase === phase && !done;
                            return (
                                <React.Fragment key={phase}>
                                    <div className="flex flex-col items-center gap-1 flex-1">
                                        <div className={`w-8 h-8 rounded-full flex items-center
                                                        justify-center text-xs font-bold
                                            ${done ? 'bg-green-500 text-white'
                                              : active ? 'bg-[var(--accent)] text-white animate-pulse'
                                              : 'bg-[var(--bg-primary)] border border-[var(--border)] text-[var(--text-muted)]'
                                            }`}>
                                            {done ? '\u2713' : i + 1}
                                        </div>
                                        <span className={`text-[10px] text-center
                                            ${active ? 'text-[var(--accent)] font-medium'
                                              : done ? 'text-green-400'
                                              : 'text-[var(--text-muted)]'
                                            }`}>
                                            {phase}
                                        </span>
                                    </div>
                                    {i < PHASES.length - 1 && (
                                        <div className={`h-0.5 flex-1 -mt-5 ${
                                            done ? 'bg-green-500' : 'bg-[var(--border)]'
                                        }`} />
                                    )}
                                </React.Fragment>
                            );
                        })}
                    </div>

                    {/* Status */}
                    <div className="text-center">
                        <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${
                            swarmStatus?.status === 'COMPLETED'
                                ? 'bg-green-500/20 text-green-400'
                                : swarmStatus?.status === 'FAILED'
                                ? 'bg-red-500/20 text-red-400'
                                : 'bg-[var(--accent)]/20 text-[var(--accent)]'
                        }`}>
                            {swarmStatus?.status ?? 'Loading...'}
                        </span>
                    </div>

                    {/* Phase history */}
                    {swarmStatus && swarmStatus.phaseHistory.length > 0 && (
                        <div className="mt-4">
                            <h3 className="text-xs font-semibold text-[var(--text-secondary)]
                                           uppercase tracking-wide mb-2">
                                History
                            </h3>
                            <div className="space-y-1">
                                {swarmStatus.phaseHistory.map((p, i) => (
                                    <div key={i} className="flex justify-between text-xs">
                                        <span className="text-green-400">{p.phase}</span>
                                        <span className="text-[var(--text-muted)]">
                                            {new Date(p.completedAt).toLocaleTimeString()}
                                        </span>
                                    </div>
                                ))}
                            </div>
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
}
