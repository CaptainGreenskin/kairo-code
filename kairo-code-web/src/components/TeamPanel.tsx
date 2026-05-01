import { useEffect, useState } from 'react';

interface TeamMember {
    memberId: string;
    name: string;
    role: string;
    sessionId: string;
}

interface SharedTask {
    taskId: string;
    title: string;
    status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED';
    ownerId: string | null;
}

interface Team {
    teamId: string;
    name: string;
    goal: string;
    members: TeamMember[];
    status: string;
}

interface TeamPanelProps {
    isOpen: boolean;
    onClose: () => void;
}

export function TeamPanel({ isOpen, onClose }: TeamPanelProps) {
    const [teams, setTeams] = useState<Team[]>([]);
    const [selectedTeam, setSelectedTeam] = useState<Team | null>(null);
    const [tasks, setTasks] = useState<SharedTask[]>([]);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        if (!isOpen) return;
        setLoading(true);
        fetch('/api/teams')
            .then(r => r.json())
            .then(setTeams)
            .catch(() => setTeams([]))
            .finally(() => setLoading(false));
    }, [isOpen]);

    useEffect(() => {
        if (!selectedTeam) return;
        fetch(`/api/teams/${selectedTeam.teamId}/tasks`)
            .then(r => r.json())
            .then(setTasks)
            .catch(() => setTasks([]));
    }, [selectedTeam]);

    if (!isOpen) return null;

    const roleColors: Record<string, string> = {
        COORDINATOR: 'text-yellow-400',
        RESEARCHER: 'text-blue-400',
        SYNTHESIZER: 'text-purple-400',
        IMPLEMENTER: 'text-green-400',
        VERIFIER: 'text-red-400',
    };

    const taskStatusIcon: Record<string, string> = {
        PENDING: '\u25CB',
        IN_PROGRESS: '\u25D1',
        COMPLETED: '\u25CF',
        FAILED: '\u2715',
    };

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
            <div className="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl
                            w-[700px] max-h-[80vh] flex flex-col shadow-2xl">
                {/* Header */}
                <div className="flex items-center justify-between px-4 py-3
                                border-b border-[var(--border)]">
                    <h2 className="text-sm font-semibold text-[var(--text-primary)]">
                        Active Teams
                    </h2>
                    <button
                        onClick={onClose}
                        className="text-[var(--text-muted)] hover:text-[var(--text-primary)]
                                   text-lg leading-none"
                        title="Close"
                    >
                        &times;
                    </button>
                </div>

                <div className="flex flex-1 overflow-hidden">
                    {/* Team list */}
                    <div className="w-48 border-r border-[var(--border)] overflow-y-auto p-2">
                        {loading && (
                            <p className="text-xs text-[var(--text-muted)] px-2 py-1">
                                Loading...
                            </p>
                        )}
                        {!loading && teams.length === 0 && (
                            <p className="text-xs text-[var(--text-muted)] px-2 py-4 text-center">
                                No active teams
                            </p>
                        )}
                        {teams.map(team => (
                            <button
                                key={team.teamId}
                                onClick={() => setSelectedTeam(team)}
                                className={`w-full text-left px-2 py-1.5 rounded text-xs
                                    ${selectedTeam?.teamId === team.teamId
                                        ? 'bg-[var(--accent)] text-white'
                                        : 'text-[var(--text-secondary)] hover:bg-[var(--bg-hover)]'
                                    }`}
                            >
                                <div className="font-medium truncate">{team.name}</div>
                                <div className="text-[10px] opacity-60 truncate">{team.status}</div>
                            </button>
                        ))}
                    </div>

                    {/* Team detail */}
                    <div className="flex-1 overflow-y-auto p-4">
                        {!selectedTeam ? (
                            <p className="text-xs text-[var(--text-muted)] text-center mt-8">
                                Select a team to view details
                            </p>
                        ) : (
                            <div className="space-y-4">
                                <div>
                                    <h3 className="text-xs font-semibold text-[var(--text-secondary)]
                                                   uppercase tracking-wide mb-1">
                                        Goal
                                    </h3>
                                    <p className="text-xs text-[var(--text-primary)]">
                                        {selectedTeam.goal || '—'}
                                    </p>
                                </div>

                                {/* Members */}
                                <div>
                                    <h3 className="text-xs font-semibold text-[var(--text-secondary)]
                                                   uppercase tracking-wide mb-2">
                                        Members ({selectedTeam.members.length})
                                    </h3>
                                    <div className="space-y-1">
                                        {selectedTeam.members.map(m => (
                                            <div key={m.memberId}
                                                 className="flex items-center gap-2 text-xs">
                                                <span className={roleColors[m.role] || 'text-[var(--text-muted)]'}>
                                                    \u25CF
                                                </span>
                                                <span className="text-[var(--text-primary)] font-medium">
                                                    {m.name}
                                                </span>
                                                <span className="text-[var(--text-muted)]">
                                                    {m.role}
                                                </span>
                                            </div>
                                        ))}
                                        {selectedTeam.members.length === 0 && (
                                            <p className="text-xs text-[var(--text-muted)]">
                                                No members yet
                                            </p>
                                        )}
                                    </div>
                                </div>

                                {/* Tasks */}
                                <div>
                                    <h3 className="text-xs font-semibold text-[var(--text-secondary)]
                                                   uppercase tracking-wide mb-2">
                                        Tasks ({tasks.length})
                                    </h3>
                                    <div className="space-y-1">
                                        {tasks.map(t => (
                                            <div key={t.taskId}
                                                 className="flex items-center gap-2 text-xs">
                                                <span className="text-[var(--text-muted)] font-mono">
                                                    {taskStatusIcon[t.status] || '?'}
                                                </span>
                                                <span className={`flex-1 truncate ${
                                                    t.status === 'COMPLETED'
                                                        ? 'line-through text-[var(--text-muted)]'
                                                        : 'text-[var(--text-primary)]'
                                                }`}>
                                                    {t.title}
                                                </span>
                                                {t.ownerId && (
                                                    <span className="text-[10px] text-[var(--text-muted)]">
                                                        {t.ownerId}
                                                    </span>
                                                )}
                                            </div>
                                        ))}
                                        {tasks.length === 0 && (
                                            <p className="text-xs text-[var(--text-muted)]">
                                                No tasks yet
                                            </p>
                                        )}
                                    </div>
                                </div>
                            </div>
                        )}
                    </div>
                </div>
            </div>
        </div>
    );
}
