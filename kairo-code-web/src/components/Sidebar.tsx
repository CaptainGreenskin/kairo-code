import { MessageSquare, Trash2 } from 'lucide-react';

interface SessionItem {
    id: string;
    name: string;
    createdAt: number;
}

interface SidebarProps {
    sessions: SessionItem[];
    activeSessionId: string | null;
    onSelectSession: (id: string) => void;
    onDeleteSession: (id: string) => void;
    onNewSession: () => void;
}

export function Sidebar({
    sessions,
    activeSessionId,
    onSelectSession,
    onDeleteSession,
    onNewSession,
}: SidebarProps) {
    return (
        <aside className="w-60 border-r border-[var(--border)] bg-[var(--bg-secondary)] flex flex-col shrink-0 hidden md:flex">
            <div className="p-3 border-b border-[var(--border)]">
                <button
                    onClick={onNewSession}
                    className="w-full px-3 py-2 text-sm font-medium text-white bg-[var(--color-primary)] hover:bg-[var(--color-primary-hover)] rounded-lg transition-colors"
                >
                    New Session
                </button>
            </div>

            <div className="flex-1 overflow-y-auto">
                {sessions.length === 0 ? (
                    <div className="p-4 text-center text-sm text-[var(--text-muted)]">
                        <MessageSquare size={20} className="mx-auto mb-2 opacity-50" />
                        No sessions yet
                    </div>
                ) : (
                    <ul className="p-2 space-y-1">
                        {sessions.map((session) => (
                            <li
                                key={session.id}
                                className={`group flex items-center justify-between px-2 py-2 rounded-lg cursor-pointer transition-colors ${
                                    session.id === activeSessionId
                                        ? 'bg-[var(--color-primary)]/10 text-[var(--color-primary)]'
                                        : 'hover:bg-[var(--bg-hover)]'
                                }`}
                                onClick={() => onSelectSession(session.id)}
                            >
                                <div className="flex-1 min-w-0">
                                    <p className="text-sm truncate">{session.name}</p>
                                    <p className="text-[10px] text-[var(--text-muted)]">
                                        {new Date(session.createdAt).toLocaleDateString()}
                                    </p>
                                </div>
                                <button
                                    onClick={(e) => {
                                        e.stopPropagation();
                                        onDeleteSession(session.id);
                                    }}
                                    className="opacity-0 group-hover:opacity-100 p-1 text-[var(--text-muted)] hover:text-[var(--color-danger)] transition-all"
                                    aria-label="Delete session"
                                >
                                    <Trash2 size={14} />
                                </button>
                            </li>
                        ))}
                    </ul>
                )}
            </div>
        </aside>
    );
}
