import { useState, useEffect, useCallback, useRef } from 'react';
import { MessageSquare, Trash2, Plus, Loader, Pencil } from 'lucide-react';
import { listSessions, deleteSession as apiDeleteSession } from '@api/config';
import type { SessionInfo } from '@/types/agent';
import { NewSessionDialog } from './NewSessionDialog';
import { formatRelativeTime } from '@utils/formatTime';
import { getSessionName, setSessionName, removeSessionName } from '@utils/sessionNames';

interface SessionSidebarProps {
    activeSessionId: string | null;
    loadingSessionId?: string | null;
    onSelectSession: (id: string) => void;
    onDeleteSession: (id: string) => void;
    onNewSession: (info: { sessionId: string; model: string }) => void;
    onCreateSession: (workingDir: string, model: string) => Promise<{ sessionId: string }>;
}

interface SessionItemProps {
    session: SessionInfo;
    isActive: boolean;
    isLoading: boolean;
    onSelect: (id: string) => void;
    onDelete: (id: string) => void;
}

function SessionItem({ session, isActive, isLoading, onSelect, onDelete }: SessionItemProps) {
    const [renaming, setRenaming] = useState(false);
    const [nameInput, setNameInput] = useState('');
    const customName = getSessionName(session.sessionId);

    const displayName = customName
        ? (customName.length > 20 ? customName.slice(0, 20) + '…' : customName)
        : `Session ${session.sessionId.slice(0, 8)}`;

    const startRename = (e: React.MouseEvent) => {
        e.stopPropagation();
        setNameInput(customName ?? '');
        setRenaming(true);
    };

    const confirmRename = () => {
        const trimmed = nameInput.trim();
        if (trimmed) {
            setSessionName(session.sessionId, trimmed);
        } else {
            removeSessionName(session.sessionId);
        }
        setRenaming(false);
    };

    return (
        <li
            className={`group flex items-center justify-between px-2 py-2 rounded-lg cursor-pointer transition-colors ${
                isActive
                    ? 'bg-[var(--color-primary)]/10 text-[var(--color-primary)]'
                    : 'hover:bg-[var(--bg-hover)]'
            } ${isLoading ? 'opacity-60' : ''}`}
            onClick={() => onSelect(session.sessionId)}
        >
            <div className="flex-1 min-w-0">
                <div className="flex items-center gap-1">
                    {renaming ? (
                        <input
                            autoFocus
                            className="text-xs px-1 py-0.5 bg-[var(--bg-primary)] border border-[var(--color-primary)] rounded outline-none text-[var(--text-primary)] w-full"
                            value={nameInput}
                            onChange={e => setNameInput(e.target.value)}
                            onKeyDown={e => {
                                if (e.key === 'Enter') confirmRename();
                                if (e.key === 'Escape') setRenaming(false);
                            }}
                            onBlur={confirmRename}
                            maxLength={40}
                            onClick={e => e.stopPropagation()}
                        />
                    ) : (
                        <span
                            className="text-sm font-mono truncate cursor-pointer"
                            onDoubleClick={startRename}
                            title={customName ?? session.sessionId}
                        >
                            {displayName}
                        </span>
                    )}
                    {isLoading && (
                        <Loader size={12} className="animate-spin shrink-0 text-[var(--color-primary)]" />
                    )}
                </div>
                <p className="text-[10px] text-[var(--text-muted)]">
                    {session.model} · {formatRelativeTime(session.createdAt)}
                    {session.running && ' · 运行中'}
                </p>
            </div>
            <div className="flex items-center gap-1">
                {!renaming && (
                    <button
                        onClick={startRename}
                        className="opacity-0 group-hover:opacity-100 p-1 text-[var(--text-muted)] hover:text-[var(--color-primary)] transition-all"
                        aria-label="Rename session"
                    >
                        <Pencil size={14} />
                    </button>
                )}
                <button
                    onClick={(e) => {
                        e.stopPropagation();
                        onDelete(session.sessionId);
                    }}
                    className="opacity-0 group-hover:opacity-100 p-1 text-[var(--text-muted)] hover:text-[var(--color-danger)] transition-all"
                    aria-label="Delete session"
                >
                    <Trash2 size={14} />
                </button>
            </div>
        </li>
    );
}

export function SessionSidebar({
    activeSessionId,
    loadingSessionId,
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

    const fetchTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    const scheduleFetch = useCallback(() => {
        if (fetchTimeoutRef.current) clearTimeout(fetchTimeoutRef.current);
        fetchTimeoutRef.current = setTimeout(fetchSessions, 300);
    }, [fetchSessions]);

    useEffect(() => {
        const handleStorage = (e: StorageEvent) => {
            if (e.key === 'kairo-session-names') {
                scheduleFetch();
            }
        };
        window.addEventListener('storage', handleStorage);
        return () => window.removeEventListener('storage', handleStorage);
    }, [scheduleFetch]);

    useEffect(() => {
        fetchSessions();
    }, [fetchSessions]);

    const handleDelete = async (id: string) => {
        if (!confirm('Delete this session?')) return;
        try {
            await apiDeleteSession(id);
            setSessions((prev) => prev.filter((s) => s.sessionId !== id));
            removeSessionName(id);
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
                                <SessionItem
                                    key={session.sessionId}
                                    session={session}
                                    isActive={session.sessionId === activeSessionId}
                                    isLoading={session.sessionId === loadingSessionId}
                                    onSelect={onSelectSession}
                                    onDelete={handleDelete}
                                />
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
