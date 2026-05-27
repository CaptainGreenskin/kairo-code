import { useEffect, useRef, useState } from 'react';
import { X, MessageSquare, Plus, PanelLeft, PanelLeftClose } from 'lucide-react';
import { useSessionStore } from '@store/sessionStore';
import { useLayoutStore } from '@store/layoutStore';
import { getSessionName } from '@utils/sessionNames';

interface ChatTabBarProps {
    /** Click + button to start a brand-new session. Receives no args. */
    onNew?: () => void;
    /** Switching active tab. */
    onActivate?: (sid: string) => void;
    /** Closing a tab. */
    onClose?: (sid: string) => void;
}

/** Returns the visible label for a session tab, falling back through layers
 *  (explicit title → saved name → first user message → short id). */
function sessionLabel(sid: string, title: string | undefined, firstUserContent: string | null): string {
    if (title && title.trim()) return title.trim();
    const saved = getSessionName(sid);
    if (saved) return saved;
    if (firstUserContent) {
        const trimmed = firstUserContent.trim().replace(/\s+/g, ' ').slice(0, 24);
        if (trimmed) return trimmed + (firstUserContent.length > 24 ? '…' : '');
    }
    return `Session ${sid.slice(0, 6)}`;
}

interface ContextMenuState {
    x: number;
    y: number;
    sid: string;
}

/**
 * VS Code / Cursor-style horizontal tab bar for open chat sessions.
 * Mirrors `EditorArea` tab UI; data source is `sessionStore.openTabs`.
 */
export function ChatTabBar({ onNew, onActivate, onClose }: ChatTabBarProps) {
    const openTabs = useSessionStore((s) => s.openTabs);
    const activeSessionId = useSessionStore((s) => s.activeSessionId);
    const sessions = useSessionStore((s) => s.sessions);
    const setActiveSession = useSessionStore((s) => s.setActiveSession);
    const closeSession = useSessionStore((s) => s.closeSession);
    const closeOtherSessions = useSessionStore((s) => s.closeOtherSessions);
    const closeAllSessions = useSessionStore((s) => s.closeAllSessions);
    const chatSessionsOpen = useLayoutStore((s) => s.chatSessionsOpen);
    const toggleChatSessions = useLayoutStore((s) => s.toggleChatSessions);

    const [ctxMenu, setCtxMenu] = useState<ContextMenuState | null>(null);
    const menuRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        if (!ctxMenu) return;
        const handler = (e: MouseEvent) => {
            if (menuRef.current && !menuRef.current.contains(e.target as Node)) setCtxMenu(null);
        };
        document.addEventListener('mousedown', handler);
        return () => document.removeEventListener('mousedown', handler);
    }, [ctxMenu]);

    const handleClick = (sid: string) => {
        if (sid !== activeSessionId) {
            setActiveSession(sid);
            onActivate?.(sid);
        }
    };

    const handleClose = (sid: string) => {
        closeSession(sid);
        onClose?.(sid);
    };

    const handleContextMenu = (e: React.MouseEvent, sid: string) => {
        e.preventDefault();
        setCtxMenu({ x: e.clientX, y: e.clientY, sid });
    };

    return (
        <div className="flex items-stretch h-9 bg-[var(--bg-secondary)] border-b border-[var(--border)] overflow-x-auto shrink-0">
            <button
                onClick={toggleChatSessions}
                title={chatSessionsOpen ? 'Hide sessions panel' : 'Show sessions panel'}
                aria-label={chatSessionsOpen ? 'Hide sessions panel' : 'Show sessions panel'}
                className="px-2 flex items-center border-r border-[var(--border)] text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)] shrink-0"
            >
                {chatSessionsOpen ? <PanelLeftClose size={14} /> : <PanelLeft size={14} />}
            </button>
            {openTabs.map((sid) => {
                const isActive = sid === activeSessionId;
                const session = sessions[sid];
                const firstUser = session?.messages.find((m) => m.role === 'user')?.content ?? null;
                const label = sessionLabel(sid, session?.title, firstUser);
                return (
                    <div
                        key={sid}
                        onClick={() => handleClick(sid)}
                        onContextMenu={(e) => handleContextMenu(e, sid)}
                        className={`group flex items-center gap-1.5 px-3 cursor-pointer border-r border-[var(--border)] text-xs whitespace-nowrap min-w-0 transition-colors ${
                            isActive
                                ? 'bg-[var(--bg-primary)] text-[var(--text-primary)]'
                                : 'text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)]'
                        }`}
                        title={label}
                    >
                        <MessageSquare size={12} className="shrink-0" />
                        <span className="truncate max-w-[140px]">{label}</span>
                        <button
                            onClick={(e) => {
                                e.stopPropagation();
                                handleClose(sid);
                            }}
                            className="ml-1 p-0.5 rounded opacity-50 hover:opacity-100 hover:bg-[var(--bg-hover)]"
                            aria-label={`Close ${label}`}
                        >
                            <X size={11} />
                        </button>
                    </div>
                );
            })}
            {onNew && (
                <button
                    onClick={onNew}
                    title="New chat"
                    aria-label="New chat"
                    className="px-2 flex items-center text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)]"
                >
                    <Plus size={13} />
                </button>
            )}

            {ctxMenu && (
                <div
                    ref={menuRef}
                    className="fixed z-[100] py-1 rounded-md border border-[var(--border)] bg-[var(--bg-secondary)] shadow-xl text-xs min-w-[140px]"
                    style={{ left: ctxMenu.x, top: ctxMenu.y }}
                >
                    <button
                        onClick={() => { handleClose(ctxMenu.sid); setCtxMenu(null); }}
                        className="w-full text-left px-3 py-1.5 hover:bg-[var(--bg-hover)] text-[var(--text-primary)]"
                    >
                        Close
                    </button>
                    <button
                        onClick={() => { closeOtherSessions(ctxMenu.sid); setCtxMenu(null); }}
                        disabled={openTabs.length <= 1}
                        className="w-full text-left px-3 py-1.5 hover:bg-[var(--bg-hover)] text-[var(--text-primary)] disabled:opacity-40 disabled:cursor-not-allowed"
                    >
                        Close Others
                    </button>
                    <button
                        onClick={() => { closeAllSessions(); setCtxMenu(null); }}
                        className="w-full text-left px-3 py-1.5 hover:bg-[var(--bg-hover)] text-[var(--text-primary)]"
                    >
                        Close All
                    </button>
                </div>
            )}
        </div>
    );
}
