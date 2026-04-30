import { useState, useEffect, useCallback } from 'react';
import { MessageSquare, Trash2, Plus } from 'lucide-react';
import { listSessions, deleteSession as apiDeleteSession } from '@api/config';
import type { SessionInfo } from '@/types/agent';
import { NewSessionDialog } from './NewSessionDialog';

interface SessionSidebarProps {
    activeSessionId: string | null;
    onSelectSession: (id: string) => void;
    onDeleteSession: (id: string) => void;
    onNewSession: (info: { sessionId: string; model: string }) => void;
    onCreateSession: (workingDir: string, model: string) => Promise<{ sessionId: string }>;
}

export function SessionSidebar({
    activeSessionId,
    onSelectSession,
    onDeleteSession,
    onNewSession,
    onCreateSession,
}: SessionSidebarProps) {
    const [sessions, setSessions] = useState<SessionInfo[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [showNewDialog, setShowNewDialog] = useState(false);

    const fetchSessions = useCallback(async () => {
        try {
            setLoading(true);
            setError(null);
            const data = await listSessions();
            setSessions(data);
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed to load sessions');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchSessions();
    }, [fetchSessions]);

    const handleDelete = async (id: string) => {
        if (!confirm('Delete this session?')) return;
        try {
            await apiDeleteSession(id);
            setSessions((prev) => prev.filter((s) => s.sessionId !== id));
            onDeleteSession(id);
        } catch (err) {
            console.error('[SessionSidebar] Failed to delete session:', err);
        }
    };

    const handleCreate = useCallback(
        (info: { sessionId: string; model: string }) => {
            setShowNewDialog(false);
            fetchSessions();
            onNewSession(info);
        },
        [fetchSessions, onNewSession],
    );

    return (
        <>
            <aside className="w-64 border-r border-[var(--border)] bg-[var(--bg-secondary)] flex flex-col shrink-0 hidden lg:flex">
                <div className="p-3 border-b border-[var(--border)]">
                    <button
                        onClick={() => setShowNewDialog(true)}
                        className="w-full px-3 py-2 text-sm font-medium text-white bg-[var(--color-primary)] hover:bg-[var(--color-primary-hover)] rounded-lg transition-colors flex items-center justify-center gap-2"
                    >
                        <Plus size={14} />
                        New Session
                    </button>
                </div>

                <div className="flex-1 overflow-y-auto">
                    {loading ? (
                        <div className="p-4 text-center text-sm text-[var(--text-muted)]">
                            Loading...
                        </div>
                    ) : error ? (
                        <div className="p-4 text-center text-sm text-[var(--color-danger)]">
                            {error}
                        </div>
                    ) : sessions.length === 0 ? (
                        <div className="p-4 text-center text-sm text-[var(--text-muted)]">
                            <MessageSquare size={20} className="mx-auto mb-2 opacity-50" />
                            No sessions yet
                        </div>
                    ) : (
                        <ul className="p-2 space-y-1">
                            {sessions.map((session) => (
                                <li
                                    key={session.sessionId}
                                    className={`group flex items-center justify-between px-2 py-2 rounded-lg cursor-pointer transition-colors ${
                                        session.sessionId === activeSessionId
                                            ? 'bg-[var(--color-primary)]/10 text-[var(--color-primary)]'
                                            : 'hover:bg-[var(--bg-hover)]'
                                    }`}
                                    onClick={() => onSelectSession(session.sessionId)}
                                >
                                    <div className="flex-1 min-w-0">
                                        <p className="text-sm font-mono truncate">
                                            {session.model}
                                        </p>
                                        <p className="text-[10px] text-[var(--text-muted)]">
                                            {new Date(session.createdAt).toLocaleString()}
                                        </p>
                                    </div>
                                    <button
                                        onClick={(e) => {
                                            e.stopPropagation();
                                            handleDelete(session.sessionId);
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

            {showNewDialog && (
                <NewSessionDialog
                    onClose={() => setShowNewDialog(false)}
                    onCreate={handleCreate}
                    onCreateSession={onCreateSession}
                />
            )}
        </>
    );
}
