import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import React from 'react';
import { MessageSquare, Trash2, Plus, Loader, Pencil, Pin, PinOff, Tag, Search, X, Archive, Sparkles } from 'lucide-react';
import { listSessions, deleteSession as apiDeleteSession, renameSession as apiRenameSession } from '@api/config';
import { useSessionStore } from '@store/sessionStore';
import type { SessionInfo } from '@/types/agent';
import { formatRelativeTime } from '@utils/formatTime';
import { getSessionName, setSessionName, removeSessionName } from '@utils/sessionNames';
import { pinSession, unpinSession, isSessionPinned, getPinnedSessions } from '@utils/sessionPins';
import { getSessionTags, addSessionTag, removeSessionTag, getAllTags } from '@utils/sessionTags';
import { sortSessions, type SessionSortOrder } from '@utils/sessionSort';
import type { SnapshotMeta } from '@utils/sessionSnapshot';

interface SessionSidebarProps {
    activeSessionId: string | null;
    loadingSessionId?: string | null;
    onSelectSession: (id: string) => void;
    onDeleteSession: (id: string) => void;
    onNewSession: (info: { sessionId: string; model: string }) => void;
    onCreateSession: (workspaceId: string) => Promise<{ sessionId: string }>;
    onSessionsChange?: (sessions: SessionInfo[]) => void;
    sortOrder?: SessionSortOrder;
    onSortChange?: (order: SessionSortOrder) => void;
    /**
     * Persisted snapshots (server-side, on-disk). Entries that are NOT also
     * present in the live `sessions` list are rendered as a "History" section.
     */
    persistedSessions?: SnapshotMeta[];
    /** Called when a History entry is clicked. Restores the snapshot. */
    onLoadSnapshot?: (sessionId: string) => void;
    /** Called after a successful server-side rename to refresh the sidebar. */
    onRenameSuccess?: (id: string, name: string) => void;
    /** Working directory of the current workspace (used to label the empty state, etc.) */
    defaultWorkingDir?: string;
    /** Currently active workspace id — when set, the sidebar filters its session list by it. */
    currentWorkspaceId?: string | null;
    /** Called when the user wants to create a new workspace (from the empty state). */
    onCreateWorkspace?: () => void;
    /** When true, the sidebar renders without its own aside chrome — meant for embedding inside a unified tabbed sidebar. */
    embedded?: boolean;
}

interface SessionItemProps {
    session: SessionInfo;
    isActive: boolean;
    isLoading: boolean;
    onSelect: (id: string) => void;
    onDelete: (id: string) => void;
    onPinChange?: () => void;
    onTagsChange?: () => void;
    onRenameSuccess?: (id: string, name: string) => void;
    searchQuery?: string;
    selectMode?: boolean;
    isSelected?: boolean;
    onToggleSelect?: (sessionId: string) => void;
    isFocused?: boolean;
    onFocus?: () => void;
}

type DateBucket = 'pinned' | 'today' | 'last7' | 'last30' | 'older';

const BUCKET_LABELS: Record<Exclude<DateBucket, 'pinned'>, string> = {
    today: 'Today',
    last7: 'Last 7 Days',
    last30: 'Last 30 Days',
    older: 'Older',
};

/** Categorize a session's createdAt timestamp into a Cursor-style bucket. */
function bucketFor(createdAt: number, now: number): Exclude<DateBucket, 'pinned'> {
    const startOfToday = new Date(now);
    startOfToday.setHours(0, 0, 0, 0);
    if (createdAt >= startOfToday.getTime()) return 'today';
    const sevenAgo = startOfToday.getTime() - 7 * 86400_000;
    if (createdAt >= sevenAgo) return 'last7';
    const thirtyAgo = startOfToday.getTime() - 30 * 86400_000;
    if (createdAt >= thirtyAgo) return 'last30';
    return 'older';
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

function SessionItem({ session, isActive, isLoading, onSelect, onDelete, onPinChange, onTagsChange, onRenameSuccess, searchQuery, selectMode, isSelected, onToggleSelect, isFocused, onFocus }: SessionItemProps) {
    const [renaming, setRenaming] = useState(false);
    const [nameInput, setNameInput] = useState('');
    const [pinned, setPinned] = useState(() => isSessionPinned(session.sessionId));
    const [nameVersion, setNameVersion] = useState(0);
    const customName = useMemo(() => getSessionName(session.sessionId), [session.sessionId, nameVersion]);
    const tags = getSessionTags(session.sessionId);
    // Cursor-style: show first user message preview under the active row.
    // Only subscribe when active to avoid re-rendering every row on every message tick.
    const activePreview = useSessionStore((s) =>
        isActive ? (s.sessions[session.sessionId]?.messages.find(m => m.role === 'user')?.content ?? null) : null
    );
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
        ? (customName.length > 40 ? customName.slice(0, 40) + '…' : customName)
        : `Session ${session.sessionId.slice(0, 8)}`;

    const startRename = (e: React.MouseEvent) => {
        e.stopPropagation();
        setNameInput(customName ?? '');
        setRenaming(true);
    };

    const confirmRename = async () => {
        const trimmed = nameInput.trim();
        if (trimmed && trimmed !== (customName ?? '')) {
            const ok = await apiRenameSession(session.sessionId, trimmed);
            if (ok) {
                onRenameSuccess?.(session.sessionId, trimmed);
            }
        }
        setSessionName(session.sessionId, nameInput);
        setNameVersion(v => v + 1);
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
            data-session-item
            tabIndex={0}
            onMouseEnter={() => onFocus?.()}
            onKeyDown={(e) => {
                if (e.key === 'F2' && isActive) {
                    e.preventDefault();
                    setNameInput(customName ?? '');
                    setRenaming(true);
                }
            }}
            className={`group relative flex items-center justify-between px-2.5 py-2 rounded-md cursor-pointer transition-colors ${
                isActive
                    ? 'bg-[var(--color-primary)]/10 text-[var(--color-primary)]'
                    : 'text-[var(--text-primary)] hover:bg-[var(--bg-hover)]'
            } ${isFocused && !isActive ? 'ring-1 ring-[var(--accent)] ring-inset' : ''} ${isLoading ? 'opacity-60' : ''} ${selectMode && isSelected ? 'ring-1 ring-[var(--accent)]' : ''}`}
            onClick={selectMode ? () => onToggleSelect?.(session.sessionId) : () => onSelect(session.sessionId)}
        >
            <div className="flex items-center gap-2 flex-1 min-w-0">
                {selectMode && (
                    <input
                        type="checkbox"
                        checked={isSelected}
                        onChange={() => onToggleSelect?.(session.sessionId)}
                        onClick={e => e.stopPropagation()}
                        className="accent-[var(--accent)] cursor-pointer"
                    />
                )}
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
                            maxLength={60}
                            onClick={e => e.stopPropagation()}
                        />
                    ) : (
                        <>
                            {pinned && <Pin size={10} className="text-[var(--color-primary)] shrink-0" />}
                            <span
                                className={`text-sm truncate cursor-pointer ${customName ? '' : 'font-mono'}`}
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
                <p className="text-[11px] text-[var(--text-muted)] mt-0.5 truncate">
                    <span className="tabular-nums">{formatRelativeTime(session.createdAt)}</span>
                    <span className="opacity-60"> · {session.model}</span>
                    {session.running && <span className="ml-1 text-[var(--color-primary)]">● 运行中</span>}
                </p>
                {isActive && activePreview && (
                    <p
                        className="text-[11px] text-[var(--text-muted)] mt-0.5 truncate opacity-80"
                        title={activePreview}
                    >
                        {activePreview.replace(/\s+/g, ' ').trim().slice(0, 60)}
                    </p>
                )}
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

interface SessionSortControlProps {
    value: SessionSortOrder;
    onChange: (order: SessionSortOrder) => void;
}

function SessionSortControl({ value, onChange }: SessionSortControlProps) {
    const options: { label: string; value: SessionSortOrder }[] = [
        { label: 'Newest', value: 'date-desc' },
        { label: 'Oldest', value: 'date-asc' },
        { label: 'A → Z', value: 'name-asc' },
        { label: 'Z → A', value: 'name-desc' },
    ];

    return (
        <select
            value={value}
            onChange={e => onChange(e.target.value as SessionSortOrder)}
            className="text-[10px] text-[var(--text-muted)] bg-transparent border-0 cursor-pointer hover:text-[var(--text-primary)] transition-colors focus:outline-none focus:ring-0 -ml-1"
            title="Sort sessions"
        >
            {options.map(o => (
                <option key={o.value} value={o.value}>{o.label}</option>
            ))}
        </select>
    );
}

export const SessionSidebar = React.memo(function SessionSidebar({
    activeSessionId,
    loadingSessionId,
    onSelectSession,
    onDeleteSession,
    onNewSession,
    onCreateSession,
    onSessionsChange,
    sortOrder = 'date-desc',
    onSortChange,
    persistedSessions,
    onLoadSnapshot,
    onRenameSuccess,
    defaultWorkingDir: _defaultWorkingDir,
    currentWorkspaceId,
    onCreateWorkspace,
    embedded = false,
}: SessionSidebarProps) {
    const [sessions, setSessions] = useState<SessionInfo[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [creatingSession, setCreatingSession] = useState(false);
    const [createError, setCreateError] = useState<string | null>(null);
    const [selectMode, setSelectMode] = useState(false);
    const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
    const [pins, setPins] = useState<string[]>(() => getPinnedSessions());
    const [allTags, setAllTags] = useState<string[]>(() => getAllTags());
    const [activeTagFilter, setActiveTagFilter] = useState<string | null>(null);
    const [sessionFilter, setSessionFilter] = useState('');
    const searchRef = useRef<HTMLInputElement>(null);
    const sessionListRef = useRef<HTMLDivElement>(null);
    const [focusedIndex, setFocusedIndex] = useState(-1);

    // Auto-scroll to focused session item
    useEffect(() => {
        if (focusedIndex < 0) return;
        const items = sessionListRef.current?.querySelectorAll('[data-session-item]');
        items?.[focusedIndex]?.scrollIntoView({ block: 'nearest' });
    }, [focusedIndex]);

    const workspaceFilteredSessions = useMemo(() => {
        if (!currentWorkspaceId) return sessions;
        // Pre-M112 sessions have no workspaceId — keep them visible in every workspace
        // (they will be hidden once they are deleted or replaced by workspace-aware sessions).
        return sessions.filter(s => !s.workspaceId || s.workspaceId === currentWorkspaceId);
    }, [sessions, currentWorkspaceId]);

    const sortedSessions = useMemo(() => {
        const pinSet = new Set(pins);
        const pinned = workspaceFilteredSessions.filter(s => pinSet.has(s.sessionId));
        const unpinned = workspaceFilteredSessions.filter(s => !pinSet.has(s.sessionId));
        // pinned 按 pins 数组顺序排列
        pinned.sort((a, b) => pins.indexOf(a.sessionId) - pins.indexOf(b.sessionId));
        // unpinned sorted by sortOrder via sortSessions
        const sortable = unpinned.map(s => ({
            id: s.sessionId,
            name: getSessionName(s.sessionId) ?? undefined,
            createdAt: s.createdAt,
        }));
        const sortedIds = new Set(sortSessions(sortable, sortOrder).map(s => s.id));
        const sortedUnpinned = [...unpinned].sort((a, b) => {
            const ai = [...sortedIds].indexOf(a.sessionId);
            const bi = [...sortedIds].indexOf(b.sessionId);
            return ai - bi;
        });
        return [...pinned, ...sortedUnpinned];
    }, [workspaceFilteredSessions, pins, sortOrder]);

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

    // History = snapshots that exist on disk but are NOT in the live session
    // list (e.g., session was unloaded/expired server-side).
    const historySnapshots = useMemo(() => {
        if (!persistedSessions || persistedSessions.length === 0) return [];
        const liveIds = new Set(sessions.map(s => s.sessionId));
        const entries = persistedSessions.filter(p => !liveIds.has(p.sessionId));
        if (!sessionFilter.trim()) return entries;
        const q = sessionFilter.toLowerCase();
        return entries.filter(p => {
            const name = (getSessionName(p.sessionId) ?? p.name ?? '').toLowerCase();
            return name.includes(q) || p.sessionId.toLowerCase().startsWith(q);
        });
    }, [persistedSessions, sessions, sessionFilter]);

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

    // Refresh when the active session changes (e.g. App.tsx created one
    // outside this component) or when the user switches workspace.
    useEffect(() => {
        if (!activeSessionId && !currentWorkspaceId) return;
        fetchSessions();
    }, [activeSessionId, currentWorkspaceId, fetchSessions]);

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

    const exitSelectMode = () => {
        setSelectMode(false);
        setSelectedIds(new Set());
    };

    const toggleSelect = (sessionId: string) => {
        setSelectedIds(prev => {
            const next = new Set(prev);
            if (next.has(sessionId)) next.delete(sessionId);
            else next.add(sessionId);
            return next;
        });
    };

    const handleBulkDelete = () => {
        if (selectedIds.size === 0) return;
        const confirmed = window.confirm(
            `Delete ${selectedIds.size} session${selectedIds.size > 1 ? 's' : ''}? This cannot be undone.`
        );
        if (!confirmed) return;
        selectedIds.forEach(id => {
            onDeleteSession(id);
            removeSessionName(id);
            unpinSession(id);
        });
        setPins(getPinnedSessions());
        fetchSessions();
        exitSelectMode();
    };

    const handleNewSessionClick = useCallback(async () => {
        setCreateError(null);
        if (!currentWorkspaceId) {
            if (onCreateWorkspace) {
                onCreateWorkspace();
            } else {
                setCreateError('No workspace selected. Open Settings to create one.');
            }
            return;
        }
        setCreatingSession(true);
        try {
            const result = await onCreateSession(currentWorkspaceId);
            await fetchSessions();
            onNewSession({ sessionId: result.sessionId, model: '' });
        } catch (err) {
            setCreateError(err instanceof Error ? err.message : String(err));
        } finally {
            setCreatingSession(false);
        }
    }, [currentWorkspaceId, onCreateSession, fetchSessions, onNewSession, onCreateWorkspace]);

    const Wrapper: React.ElementType = embedded ? 'div' : 'aside';
    const wrapperClass = embedded
        ? 'flex-1 flex flex-col min-h-0 bg-[var(--bg-secondary)]'
        : 'w-64 border-r border-[var(--border)] bg-[var(--bg-secondary)] flex flex-col shrink-0 hidden lg:flex';
    return (
        <>
            <Wrapper className={wrapperClass}>
                {/* Top: full-width "+ New Agent" CTA (Cursor style). */}
                <div className="px-3 pt-3 pb-2">
                    <button
                        onClick={handleNewSessionClick}
                        disabled={creatingSession}
                        className="w-full h-9 inline-flex items-center justify-center gap-2 rounded-md bg-[var(--bg-primary)] border border-[var(--border)] text-sm text-[var(--text-primary)] hover:bg-[var(--bg-hover)] hover:border-[var(--accent)] transition-colors disabled:opacity-60"
                        title="Start a new agent chat"
                        aria-label="New agent"
                    >
                        {creatingSession
                            ? <Loader size={14} className="animate-spin" />
                            : <Sparkles size={14} className="text-[var(--accent)]" />}
                        <span className="font-medium">New Agent</span>
                    </button>
                </div>

                {/* Search */}
                <div className="px-3 pb-2">
                    <div className="relative">
                        <Search size={12} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[var(--text-muted)]" />
                        <input
                            ref={searchRef}
                            type="text"
                            value={sessionFilter}
                            onChange={e => setSessionFilter(e.target.value)}
                            onKeyDown={e => { if (e.key === 'Escape') setSessionFilter(''); }}
                            placeholder="Search Agents…"
                            className="w-full pl-7 pr-7 h-7 text-xs rounded-md bg-[var(--bg-primary)] border border-[var(--border)] text-[var(--text-primary)] placeholder:text-[var(--text-muted)] outline-none focus:border-[var(--accent)] transition-colors"
                        />
                        {sessionFilter && (
                            <button
                                onClick={() => setSessionFilter('')}
                                className="absolute right-2 top-1/2 -translate-y-1/2 text-[var(--text-muted)] hover:text-[var(--text-primary)]"
                                aria-label="Clear filter"
                            >
                                <X size={11} />
                            </button>
                        )}
                    </div>
                </div>

                {/* Tools row — sort + bulk select, right-aligned and muted */}
                <div className="flex items-center justify-between px-3 pb-2">
                    <div className="flex items-center">
                        {onSortChange && (
                            <SessionSortControl value={sortOrder} onChange={onSortChange} />
                        )}
                    </div>
                    <button
                        onClick={() => selectMode ? exitSelectMode() : setSelectMode(true)}
                        className={`text-[10px] px-1.5 py-0.5 rounded transition-colors ${
                            selectMode
                                ? 'bg-[var(--accent)] text-white'
                                : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]'
                        }`}
                    >
                        {selectMode ? 'Cancel' : 'Select'}
                    </button>
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

                {selectMode && selectedIds.size > 0 && (
                    <div className="flex items-center justify-between px-3 py-2 bg-[var(--bg-secondary)] border-b border-[var(--border)]">
                        <span className="text-xs text-[var(--text-muted)]">
                            {selectedIds.size} selected
                        </span>
                        <button
                            onClick={handleBulkDelete}
                            className="text-xs px-2 py-1 rounded bg-red-500/10 text-red-400 hover:bg-red-500/20 transition-colors flex items-center gap-1"
                        >
                            <Trash2 size={11} />
                            Delete {selectedIds.size}
                        </button>
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
                    ) : workspaceFilteredSessions.length === 0 && historySnapshots.length === 0 ? (
                        <div className="px-4 py-8 text-center">
                            <div className="mx-auto mb-3 w-10 h-10 rounded-full bg-[var(--bg-hover)] flex items-center justify-center">
                                <MessageSquare size={18} className="text-[var(--text-muted)]" />
                            </div>
                            <p className="text-sm text-[var(--text-secondary)] mb-1">No sessions yet</p>
                            <p className="text-[11px] text-[var(--text-muted)] mb-3">
                                Start a new chat in this workspace
                            </p>
                            <button
                                onClick={handleNewSessionClick}
                                disabled={creatingSession}
                                className="inline-flex items-center gap-1.5 px-2.5 py-1 text-xs rounded-md bg-[var(--color-primary)] text-white hover:bg-[var(--color-primary-hover)] transition-colors disabled:opacity-60"
                            >
                                {creatingSession ? <Loader size={12} className="animate-spin" /> : <Plus size={12} />}
                                New session
                            </button>
                        </div>
                    ) : (
                        <>
                            <div
                                ref={sessionListRef}
                                tabIndex={-1}
                                onKeyDown={(e) => {
                                    if (filteredSessions.length === 0) return;

                                    if (e.key === 'ArrowDown') {
                                        e.preventDefault();
                                        const next = e.metaKey || e.ctrlKey
                                            ? filteredSessions.length - 1
                                            : Math.min(focusedIndex + 1, filteredSessions.length - 1);
                                        setFocusedIndex(next);
                                    } else if (e.key === 'ArrowUp') {
                                        e.preventDefault();
                                        const prev = e.metaKey || e.ctrlKey
                                            ? 0
                                            : Math.max(focusedIndex - 1, 0);
                                        setFocusedIndex(prev);
                                    } else if (e.key === 'Enter' && focusedIndex >= 0) {
                                        e.preventDefault();
                                        onSelectSession(filteredSessions[focusedIndex].sessionId);
                                    }
                                }}
                            >
                                {(() => {
                                    // Bucket sessions by createdAt — pinned items keep their own group at the top.
                                    const pinSet = new Set(pins);
                                    const now = Date.now();
                                    const groups: { key: DateBucket; label: string; items: SessionInfo[] }[] = [
                                        { key: 'pinned', label: 'Pinned', items: [] },
                                        { key: 'today', label: BUCKET_LABELS.today, items: [] },
                                        { key: 'last7', label: BUCKET_LABELS.last7, items: [] },
                                        { key: 'last30', label: BUCKET_LABELS.last30, items: [] },
                                        { key: 'older', label: BUCKET_LABELS.older, items: [] },
                                    ];
                                    for (const s of filteredSessions) {
                                        if (pinSet.has(s.sessionId)) {
                                            groups[0].items.push(s);
                                        } else {
                                            const b = bucketFor(s.createdAt, now);
                                            const grp = groups.find((g) => g.key === b)!;
                                            grp.items.push(s);
                                        }
                                    }
                                    // Track running index across groups so keyboard focus stays on the global list.
                                    let flatIdx = 0;
                                    return groups
                                        .filter((g) => g.items.length > 0)
                                        .map((g) => (
                                            <div key={g.key} className="mb-2">
                                                <div className="px-3 pt-2 pb-1 text-[10px] uppercase tracking-wide text-[var(--text-muted)]">
                                                    {g.label}
                                                </div>
                                                <ul className="px-2 space-y-1">
                                                    {g.items.map((session) => {
                                                        const indexHere = flatIdx++;
                                                        return (
                                                            <SessionItem
                                                                key={session.sessionId}
                                                                session={session}
                                                                isActive={session.sessionId === activeSessionId}
                                                                isLoading={session.sessionId === loadingSessionId}
                                                                onSelect={onSelectSession}
                                                                onDelete={handleDelete}
                                                                onPinChange={handlePinChange}
                                                                onTagsChange={handleTagsChange}
                                                                onRenameSuccess={onRenameSuccess}
                                                                searchQuery={sessionFilter}
                                                                selectMode={selectMode}
                                                                isSelected={selectedIds.has(session.sessionId)}
                                                                onToggleSelect={toggleSelect}
                                                                isFocused={indexHere === focusedIndex}
                                                                onFocus={() => setFocusedIndex(indexHere)}
                                                            />
                                                        );
                                                    })}
                                                </ul>
                                            </div>
                                        ));
                                })()}
                            </div>
                            {filteredSessions.length === 0 && sessionFilter && (
                                <div className="px-3 py-4 text-xs text-[var(--text-muted)] text-center">
                                    No sessions match &#8220;{sessionFilter}&#8221;
                                </div>
                            )}

                            {historySnapshots.length > 0 && (
                                <div className="mt-2 border-t border-[var(--border)]">
                                    <div className="flex items-center gap-1.5 px-3 pt-3 pb-1 text-[10px] uppercase tracking-wide text-[var(--text-muted)]">
                                        <Archive size={10} />
                                        History
                                    </div>
                                    <ul className="px-2 pb-2 space-y-1">
                                        {historySnapshots.map(snap => {
                                            const customName = getSessionName(snap.sessionId);
                                            const display = customName
                                                ?? (snap.name && snap.name.length > 0
                                                    ? snap.name
                                                    : `Session ${snap.sessionId.slice(0, 8)}`);
                                            const isLoading = snap.sessionId === loadingSessionId;
                                            const isActive = snap.sessionId === activeSessionId;
                                            return (
                                                <li
                                                    key={`history-${snap.sessionId}`}
                                                    onClick={() => onLoadSnapshot?.(snap.sessionId)}
                                                    className={`group flex items-center justify-between px-2 py-1.5 rounded-lg cursor-pointer transition-colors ${
                                                        isActive
                                                            ? 'bg-[var(--color-primary)]/10 text-[var(--color-primary)]'
                                                            : 'hover:bg-[var(--bg-hover)]'
                                                    } ${isLoading ? 'opacity-60' : ''}`}
                                                    title={`Restore snapshot saved ${formatRelativeTime(snap.savedAt)}`}
                                                >
                                                    <div className="flex-1 min-w-0">
                                                        <div className="flex items-center gap-1">
                                                            <span className="text-xs font-mono truncate text-[var(--text-secondary)]">
                                                                {highlightMatch(
                                                                    display.length > 40 ? display.slice(0, 40) + '…' : display,
                                                                    sessionFilter,
                                                                )}
                                                            </span>
                                                            {isLoading && (
                                                                <Loader size={11} className="animate-spin shrink-0 text-[var(--color-primary)]" />
                                                            )}
                                                        </div>
                                                        <p className="text-[10px] text-[var(--text-muted)]">
                                                            {snap.messageCount} msg · {formatRelativeTime(snap.savedAt)}
                                                        </p>
                                                    </div>
                                                </li>
                                            );
                                        })}
                                    </ul>
                                </div>
                            )}
                        </>
                    )}
                </div>
            </Wrapper>

            {createError && (
                <div className="fixed bottom-4 left-4 z-50 px-3 py-2 text-xs text-[var(--color-danger)] bg-[var(--color-danger-bg)] rounded-lg shadow">
                    {createError}
                </div>
            )}
        </>
    );
});
