import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { MessageSquare, Trash2, Plus, Loader, Pencil, Pin, PinOff, Tag, Search, X } from 'lucide-react';
import { listSessions, deleteSession as apiDeleteSession } from '@api/config';
import type { SessionInfo } from '@/types/agent';
import { NewSessionDialog } from './NewSessionDialog';
import { formatRelativeTime } from '@utils/formatTime';
import { getSessionName, setSessionName, removeSessionName } from '@utils/sessionNames';
import { pinSession, unpinSession, isSessionPinned, getPinnedSessions } from '@utils/sessionPins';
import { getSessionTags, addSessionTag, removeSessionTag, getAllTags } from '@utils/sessionTags';

interface SessionSidebarProps {
    activeSessionId: string | null;
    loadingSessionId?: string | null;
    onSelectSession: (id: string) => void;
    onDeleteSession: (id: string) => void;
    onNewSession: (info: { sessionId: string; model: string }) => void;
    onCreateSession: (workingDir: string, model: string) => Promise<{ sessionId: string }>;
    onSessionsChange?: (sessions: SessionInfo[]) => void;
}

interface SessionItemProps {
    session: SessionInfo;
    isActive: boolean;
    isLoading: boolean;
    onSelect: (id: string) => void;
    onDelete: (id: string) => void;
    onPinChange?: () => void;
    onTagsChange?: () => void;
    searchQuery?: string;
}

function highlightMatch(text: string, query: string): React.ReactNode {
    if (!query) return text;
    const idx = text.toLowerCase().indexOf(query.toLowerCase());
    if (idx === -1) return text;
    return (
        <>
            {text.slice(0, idx)}
            <mark className="bg-[var(--accent)]/30 text-[var(--text-primary)] rounded-sm not-italic">
                {text.slice(idx, idx + query.length)}
            </mark>
            {text.slice(idx + query.length)}
        </>
    );
}

function SessionItem({ session, isActive, isLoading, onSelect, onDelete, onPinChange, onTagsChange, searchQuery }: SessionItemProps) {
    const [renaming, setRenaming] = useState(false);
    const [nameInput, setNameInput] = useState('');
    const [pinned, setPinned] = useState(() => isSessionPinned(session.sessionId));
    const customName = getSessionName(session.sessionId);
    const tags = getSessionTags(session.sessionId);
    const [addingTag, setAddingTag] = useState(false);
    const [tagInput, setTagInput] = useState('');

    const handleAddTag = () => {
        const tag = tagInput.trim().toLowerCase();
        if (tag && tag.length <= 20) {
            addSessionTag(session.sessionId, tag);
            onTagsChange?.();
        }
        setAddingTag(false);
        setTagInput('');
    };

    const handleRemoveTag = (e: React.MouseEvent, tag: string) => {
        e.stopPropagation();
        removeSessionTag(session.sessionId, tag);
        onTagsChange?.();
    };

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

    const handleTogglePin = (e: React.MouseEvent) => {
        e.stopPropagation();
        if (pinned) {
            unpinSession(session.sessionId);
            setPinned(false);
        } else {
            pinSession(session.sessionId);
            setPinned(true);
        }
        onPinChange?.();
    };

    return (
        <li
            tabIndex={0}
            onKeyDown={(e) => {
                if (e.key === 'F2' && isActive) {
                    e.preventDefault();
                    setNameInput(customName ?? '');
                    setRenaming(true);
                }
            }}
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
                            ref={el => { if (el) { el.focus(); el.select(); } }}
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
                        <>
                            {pinned && <Pin size={10} className="text-[var(--color-primary)] shrink-0" />}
                            <span
                                className="text-sm font-mono truncate cursor-pointer"
                                onDoubleClick={startRename}
                                title={customName ?? session.sessionId}
                            >
                                {highlightMatch(displayName, searchQuery ?? '')}
                            </span>
                        </>
                    )}
                    {isLoading && (
                        <Loader size={12} className="animate-spin shrink-0 text-[var(--color-primary)]" />
                    )}
                </div>
                <p className="text-[10px] text-[var(--text-muted)]">
                    {session.model} · {formatRelativeTime(session.createdAt)}
                    {session.running && ' · 运行中'}
                </p>
                {tags.length > 0 && (
                    <div className="flex flex-wrap gap-1 mt-0.5">
                        {tags.slice(0, 3).map(tag => (
                            <span
                                key={tag}
                                onClick={(e) => handleRemoveTag(e, tag)}
                                className="text-[9px] px-1 py-0.5 rounded bg-[var(--bg-secondary)] text-[var(--text-muted)] cursor-pointer hover:text-[var(--color-danger)] transition-colors"
                                title={`Remove tag #${tag}`}
                            >
                                #{tag}
                            </span>
                        ))}
                        {tags.length > 3 && <span className="text-[9px] text-[var(--text-muted)]">+{tags.length - 3}</span>}
                    </div>
                )}
            </div>
            <div className="flex items-center gap-1">
                <button
                    onClick={() => setAddingTag(true)}
                    className="opacity-0 group-hover:opacity-100 p-1 text-[var(--text-muted)] hover:text-[var(--accent)] transition-all"
                    aria-label="Add tag"
                >
                    <Tag size={11} />
                </button>
                {addingTag && (
                    <input
                        autoFocus
                        value={tagInput}
                        onChange={e => setTagInput(e.target.value)}
                        onKeyDown={e => {
                            if (e.key === 'Enter') handleAddTag();
                            if (e.key === 'Escape') { setAddingTag(false); setTagInput(''); }
                        }}
                        onBlur={handleAddTag}
                        placeholder="tag name..."
                        className="text-xs px-1 py-0.5 rounded bg-[var(--bg-secondary)] border border-[var(--accent)] outline-none w-24"
                        maxLength={20}
                        onClick={e => e.stopPropagation()}
                    />
                )}
                {!addingTag && (
                    <button
                        onClick={handleTogglePin}
                        className="opacity-0 group-hover:opacity-100 p-1 text-[var(--text-muted)] hover:text-[var(--color-primary)] transition-all"
                        aria-label={pinned ? 'Unpin session' : 'Pin to top'}
                    >
                        {pinned ? <PinOff size={12} /> : <Pin size={12} />}
                    </button>
                )}
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
    onSessionsChange,
}: SessionSidebarProps) {
    const [sessions, setSessions] = useState<SessionInfo[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [showNewDialog, setShowNewDialog] = useState(false);
    const [pins, setPins] = useState<string[]>(() => getPinnedSessions());
    const [allTags, setAllTags] = useState<string[]>(() => getAllTags());
    const [activeTagFilter, setActiveTagFilter] = useState<string | null>(null);
    const [sessionFilter, setSessionFilter] = useState('');
    const searchRef = useRef<HTMLInputElement>(null);

    const sortedSessions = useMemo(() => {
        const pinSet = new Set(pins);
        const pinned = sessions.filter(s => pinSet.has(s.sessionId));
        const unpinned = sessions.filter(s => !pinSet.has(s.sessionId));
        // pinned 按 pins 数组顺序排列
        pinned.sort((a, b) => pins.indexOf(a.sessionId) - pins.indexOf(b.sessionId));
        return [...pinned, ...unpinned];
    }, [sessions, pins]);

    const displayedSessions = useMemo(() => {
        if (!activeTagFilter) return sortedSessions;
        return sortedSessions.filter(s =>
            getSessionTags(s.sessionId).includes(activeTagFilter)
        );
    }, [sortedSessions, activeTagFilter]);

    const filteredSessions = useMemo(() => {
        if (!sessionFilter.trim()) return displayedSessions;
        const q = sessionFilter.toLowerCase();
        return displayedSessions.filter(s => {
            const name = getSessionName(s.sessionId) ?? `session ${s.sessionId.slice(0, 8)}`;
            return name.toLowerCase().includes(q) ||
                s.sessionId.toLowerCase().startsWith(q);
        });
    }, [displayedSessions, sessionFilter]);

    const handlePinChange = useCallback(() => {
        setPins(getPinnedSessions());
    }, []);

    const handleTagsChange = useCallback(() => {
        setAllTags(getAllTags());
    }, []);

    const updateSessions = useCallback((newSessions: SessionInfo[]) => {
        setSessions(newSessions);
        onSessionsChange?.(newSessions);
    }, [onSessionsChange]);

    const fetchSessions = useCallback(async () => {
        try {
            setLoading(true);
            setError(null);
            const data = await listSessions();
            updateSessions(data);
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed to load sessions');
        } finally {
            setLoading(false);
        }
    }, [updateSessions]);

    const fetchTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    const scheduleFetch = useCallback(() => {
        if (fetchTimeoutRef.current) clearTimeout(fetchTimeoutRef.current);
        fetchTimeoutRef.current = setTimeout(fetchSessions, 300);
    }, [fetchSessions]);

    useEffect(() => {
        const handleStorage = (e: StorageEvent) => {
            if (e.key === 'kairo-session-names' || e.key === 'kairo-session-pins') {
                scheduleFetch();
            }
        };
        window.addEventListener('storage', handleStorage);
        return () => window.removeEventListener('storage', handleStorage);
    }, [scheduleFetch]);

    useEffect(() => {
        fetchSessions();
    }, [fetchSessions]);

    // Cmd+Shift+S to focus sidebar search
    useEffect(() => {
        const handler = (e: KeyboardEvent) => {
            if ((e.metaKey || e.ctrlKey) && e.shiftKey && e.key === 'S') {
                e.preventDefault();
                searchRef.current?.focus();
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, []);

    const handleDelete = async (id: string) => {
        if (!confirm('Delete this session?')) return;
        try {
            await apiDeleteSession(id);
            const remaining = sessions.filter((s) => s.sessionId !== id);
            updateSessions(remaining);
            removeSessionName(id);
            unpinSession(id);
            setPins(getPinnedSessions());
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

                <div className="px-3 py-2">
                    <div className="relative">
                        <Search size={12} className="absolute left-2 top-1/2 -translate-y-1/2 text-[var(--text-muted)]" />
                        <input
                            ref={searchRef}
                            type="text"
                            value={sessionFilter}
                            onChange={e => setSessionFilter(e.target.value)}
                            onKeyDown={e => { if (e.key === 'Escape') setSessionFilter(''); }}
                            placeholder="Filter sessions…"
                            className="w-full pl-6 pr-2 py-1 text-xs rounded bg-[var(--bg-secondary)] text-[var(--text-primary)] placeholder:text-[var(--text-muted)] outline-none focus:ring-1 focus:ring-[var(--accent)]"
                        />
                        {sessionFilter && (
                            <button
                                onClick={() => setSessionFilter('')}
                                className="absolute right-2 top-1/2 -translate-y-1/2 text-[var(--text-muted)] hover:text-[var(--text-primary)]"
                            >
                                <X size={10} />
                            </button>
                        )}
                    </div>
                </div>

                {allTags.length > 0 && (
                    <div className="flex flex-wrap gap-1 px-3 py-2 border-b border-[var(--border)]">
                        {allTags.map(tag => (
                            <button
                                key={tag}
                                onClick={() => setActiveTagFilter(f => f === tag ? null : tag)}
                                className={`px-2 py-0.5 text-[10px] rounded-full transition-colors ${
                                    activeTagFilter === tag
                                        ? 'bg-[var(--accent)] text-white'
                                        : 'bg-[var(--bg-secondary)] text-[var(--text-secondary)] hover:text-[var(--text-primary)]'
                                }`}
                            >
                                #{tag}
                            </button>
                        ))}
                    </div>
                )}

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
                        <>
                            <ul className="p-2 space-y-1">
                                {(() => {
                                    const pinSet = new Set(pins);
                                    const pinnedCount = filteredSessions.filter(s => pinSet.has(s.sessionId)).length;

                                    return filteredSessions.map((session, index) => {
                                        const isPinned = pinSet.has(session.sessionId);
                                        const showDivider = isPinned && index === pinnedCount - 1 && pinnedCount < filteredSessions.length;

                                        return (
                                            <div key={session.sessionId}>
                                                <SessionItem
                                                    session={session}
                                                    isActive={session.sessionId === activeSessionId}
                                                    isLoading={session.sessionId === loadingSessionId}
                                                    onSelect={onSelectSession}
                                                    onDelete={handleDelete}
                                                    onPinChange={handlePinChange}
                                                    onTagsChange={handleTagsChange}
                                                    searchQuery={sessionFilter}
                                                />
                                                {showDivider && (
                                                    <div className="mx-3 my-1 border-t border-[var(--border)]" />
                                                )}
                                            </div>
                                        );
                                    });
                                })()}
                            </ul>
                            {filteredSessions.length === 0 && sessionFilter && (
                                <div className="px-3 py-4 text-xs text-[var(--text-muted)] text-center">
                                    No sessions match &#8220;{sessionFilter}&#8221;
                                </div>
                            )}
                        </>
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
