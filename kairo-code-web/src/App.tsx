import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ArrowDown, Plus, Search, FolderTree, Settings, Moon, HelpCircle, FileText, Clipboard, SortAsc, ArrowLeft, ArrowRight, BookOpen, GitBranch, Terminal, Settings2, Brain, Zap, Wrench, Activity, Star, Users } from 'lucide-react';
import { useSessionStore } from '@store/sessionStore';
import { useWorkspaceStore } from '@store/workspaceStore';
import { useLayoutStore } from '@store/layoutStore';
import { useOpenFilesStore } from '@store/openFilesStore';
import { useAgentWebSocket } from '@hooks/useAgentWebSocket';
import { useBreakpoint } from '@hooks/useBreakpoint';
import { Header } from '@components/Header';
import { DevDiagnosticsPanel } from '@components/DevDiagnosticsPanel';
import { SearchBar } from '@components/SearchBar';
import { ChatMessage } from '@components/ChatMessage';
import { ThinkingIndicator } from '@components/ThinkingIndicator';
import { LastToolDisplay } from '@components/LastToolDisplay';
import type { Phase } from '@components/ThinkingIndicator';
import { ChatInput } from '@components/ChatInput';
import type { AttachedImage } from '@components/ChatInput';
import { ChatTabBar } from '@components/ChatTabBar';
import { SessionSidebar } from '@components/SessionSidebar';
import { SettingsModal } from '@components/SettingsModal';
import { WorkspaceSwitcher } from '@components/WorkspaceSwitcher';
import { WorkspaceSettingsModal } from '@components/WorkspaceSettingsModal';
import { ActivityBar } from '@components/ActivityBar';
import { PrimarySidebar } from '@components/PrimarySidebar';
import { BottomPanel } from '@components/BottomPanel';
import { StatusBar } from '@components/StatusBar';
import { WorkspacesView } from '@components/WorkspacesView';
import { FilesView } from '@components/FilesView';
import { EditorArea } from '@components/EditorArea';
import { ResizeHandle } from '@components/ResizeHandle';
import { SearchPanel } from '@components/SearchPanel';
import { CommandPalette } from '@components/CommandPalette';
import { ShortcutsModal } from '@components/ShortcutsModal';
import { PendingApprovalBanner } from '@components/PendingApprovalBanner';
import { MessageSearchBar } from '@components/MessageSearchBar';
import { WelcomeScreen } from '@components/WelcomeScreen';
import { ErrorBoundary } from '@components/ErrorBoundary';
import { ToastContainer, type ToastMessage } from '@components/Toast';
import type { Command } from '@components/CommandPalette';
import { MemoryEditorPanel } from '@components/MemoryEditorPanel';
import { PromptTemplatesPanel } from '@components/PromptTemplatesPanel';
import { GitStatusPanel } from '@components/GitStatusPanel';
import { McpServersPanel } from '@components/McpServersPanel';
import { TodoListPanel } from '@components/TodoListPanel';
import { EvolutionPanel } from '@components/EvolutionPanel';
import { HookConfigPanel } from '@components/HookConfigPanel';
import { ToolStatsDashboard } from '@components/ToolStatsDashboard';
import { SessionSearchPanel } from '@components/SessionSearchPanel';
import { TeamPanel } from '@components/TeamPanel';
import { ExportMenu } from '@components/ExportMenu';
import { ExecutionTimeline } from '@components/ExecutionTimeline';
import { BookmarkPanel } from '@components/BookmarkPanel';
import { getBookmarks, toggleBookmark } from '@utils/bookmarkMessages';
import type { Message, ServerConfig } from '@/types/agent';
import { getConfig } from '@api/config';
import { exportAndDownload, copySessionToClipboard } from '@utils/exportSession';
import { estimateMessagesTokens } from '@utils/tokenCount';
import { getContextWindow } from '@utils/tokenBudget';
import { searchMessages } from '@utils/messageSearch';
import type { MessageSearchResult } from '@utils/messageSearch';
import { Virtuoso } from 'react-virtuoso';
import { saveMessages, loadMessages, clearMessages as clearCachedMessages } from '@utils/messageCache';
import {
    loadSnapshot,
    listSnapshots,
    deleteSnapshot,
    setLastSessionId,
    getLastSessionId,
    clearLastSessionId,
    type SnapshotMeta,
} from '@utils/sessionSnapshot';
import { getSessionName } from '@utils/sessionNames';
import { autoNameSession } from '@utils/sessionAutoName';
import { loadPrefs, savePref } from '@utils/userPrefs';
import { loadDraft } from '@utils/inputDraft';
import { isCollapsible } from '@utils/messageCollapse';
import { sortSessions, type SessionSortOrder } from '@utils/sessionSort';
import { useAgentNotification } from '@hooks/useAgentNotification';
import { useFileTracker } from '@hooks/useFileTracker';
import { useAgentEventHandler } from '@hooks/useAgentEventHandler';
import { streamingStore } from '@store/streamingStore';
import { useGlobalShortcuts } from '@hooks/useGlobalShortcuts';
import { FileTrackerBadge } from '@components/FileTrackerBadge';

declare const __APP_VERSION__: string;

function generateId(): string {
    return crypto.randomUUID();
}

function App() {
    const prefs = loadPrefs();

    const todos = useSessionStore((s) => s.todos);
    const running = useSessionStore((s) => s.running);
    const {
        sessionId,
        messages,
        isThinking,
        thinkingText,
        tokenUsage,
        estimatedCost,
        currentModel,
        setSessionId,
        addMessage,
        setMessages,
        setThinking,
        setTokenUsage,
        setEstimatedCost,
        setCurrentModel,
        clearMessages,
        restoreSession,
    } = useSessionStore();

    // Per-session map of streaming assistant message ids (sessionId → msgId or null).
    // A single ref would cross-pollinate when two sessions stream concurrently or the
    // user switches tabs mid-stream.
    const assistantMsgRef = useRef<Record<string, string | null>>({});
    const hasErrorRef = useRef(false);
    const wasConnectedRef = useRef(false);
    const [streamingMsgId, setStreamingMsgId] = useState<string | null>(null);
    const [agentPhase, setAgentPhase] = useState<Phase>('thinking');
    const [currentToolName, setCurrentToolName] = useState<string | undefined>(undefined);
    // Briefly true after a CONTEXT_COMPACTED event so the Header indicator can flash.
    const [isCompacting, setIsCompacting] = useState(false);
    const compactionTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    useEffect(() => {
        return () => {
            if (compactionTimerRef.current) clearTimeout(compactionTimerRef.current);
        };
    }, []);

    // Tool execution timing
    const toolStartTimeRef = useRef<number | null>(null);
    const [toolElapsed, setToolElapsed] = useState(0);
    const [lastTool, setLastTool] = useState<{ name: string; elapsed: number } | null>(null);

    useEffect(() => {
        if (agentPhase === 'tool') {
            toolStartTimeRef.current = Date.now();
            const timer = setInterval(() => {
                setToolElapsed(Date.now() - (toolStartTimeRef.current ?? Date.now()));
            }, 100);
            return () => clearInterval(timer);
        } else {
            if (toolStartTimeRef.current) {
                const elapsed = Date.now() - toolStartTimeRef.current;
                setLastTool({ name: currentToolName || 'tool', elapsed });
                setTimeout(() => setLastTool(null), 2000);
            }
            toolStartTimeRef.current = null;
            setToolElapsed(0);
        }
    }, [agentPhase, currentToolName]);
    const [showSettings, setShowSettings] = useState(false);
    const [showWorkspaceSettings, setShowWorkspaceSettings] = useState<{ open: boolean; workspaceId: string | null }>({ open: false, workspaceId: null });
    const [serverConfig, setServerConfig] = useState<ServerConfig | null>(null);

    const workspaces = useWorkspaceStore((s) => s.workspaces);
    const currentWorkspaceId = useWorkspaceStore((s) => s.currentWorkspaceId);
    const refreshWorkspaces = useWorkspaceStore((s) => s.refresh);
    const currentWorkspace = useMemo(
        () => workspaces.find((w) => w.id === currentWorkspaceId) ?? null,
        [workspaces, currentWorkspaceId],
    );
    const sidebarWidth = useLayoutStore((s) => s.sidebarWidth);
    const setSidebarWidthStore = useLayoutStore((s) => s.setSidebarWidth);
    const primarySidebarOpen = useLayoutStore((s) => s.primarySidebarOpen);
    const bottomPanelOpen = useLayoutStore((s) => s.bottomPanelOpen);
    const toggleBottomPanel = useLayoutStore((s) => s.toggleBottomPanel);
    const bottomHeight = useLayoutStore((s) => s.bottomHeight);
    const setBottomHeight = useLayoutStore((s) => s.setBottomHeight);
    const selectActivity = useLayoutStore((s) => s.selectActivity);
    const chatSidebarOpen = useLayoutStore((s) => s.chatSidebarOpen);
    const chatWidth = useLayoutStore((s) => s.chatWidth);
    const setChatWidthStore = useLayoutStore((s) => s.setChatWidth);
    const chatSessionsOpen = useLayoutStore((s) => s.chatSessionsOpen);
    const chatSessionsWidth = useLayoutStore((s) => s.chatSessionsWidth);
    const setChatSessionsWidthStore = useLayoutStore((s) => s.setChatSessionsWidth);
    const openFileInEditor = useOpenFilesStore((s) => s.openFile);
    const [showSearch, setShowSearch] = useState(false);
    const [searchQuery, setSearchQuery] = useState('');
    const [showMessageSearch, setShowMessageSearch] = useState(false);
    const [messageSearchQuery, setMessageSearchQuery] = useState('');
    const [messageSearchResults, setMessageSearchResults] = useState<MessageSearchResult[]>([]);
    const [messageSearchMatchIndex, setMessageSearchMatchIndex] = useState(0);
    const [showSessionSearch, setShowSessionSearch] = useState(false);
    const [chatInputAppend, setChatInputAppend] = useState<string>('');
    const [loadingSessionId, setLoadingSessionId] = useState<string | null>(null);

    // Expanded messages state for collapsible messages
    const [expandedMessages, setExpandedMessages] = useState<Set<string>>(new Set());

    const handleToggleMessageExpand = useCallback((messageId: string) => {
        setExpandedMessages(prev => {
            const next = new Set(prev);
            if (next.has(messageId)) next.delete(messageId); else next.add(messageId);
            return next;
        });
    }, []);
    const [showCommandPalette, setShowCommandPalette] = useState(false);
    const [showShortcuts, setShowShortcuts] = useState(false);
    const [sidebarSessions, setSidebarSessions] = useState<Array<{ sessionId: string }>>([]);
    const [persistedSessions, setPersistedSessions] = useState<SnapshotMeta[]>([]);
    const [sessionSortOrder, setSessionSortOrder] = useState<SessionSortOrder>('date-desc');

    const refreshPersistedSessions = useCallback(async () => {
        const metas = await listSnapshots();
        setPersistedSessions(metas);
    }, []);
    const virtuosoRef = useRef<import('react-virtuoso').VirtuosoHandle>(null);
    const [atBottom, setAtBottom] = useState(true);
    const [unreadCount, setUnreadCount] = useState(0);
    const prevMsgCount = useRef(messages.length);

    // Sorted sessions for keyboard navigation (Cmd+[ / Cmd+])
    const sortedSessions = useMemo(
        () => sortSessions(
            sidebarSessions.map(s => ({
                id: s.sessionId,
                name: getSessionName(s.sessionId) ?? undefined,
            })),
            sessionSortOrder,
        ),
        [sidebarSessions, sessionSortOrder],
    );

    // Toast state
    const [toasts, setToasts] = useState<ToastMessage[]>([]);

    // Todo panel collapse pref (sticky panel above message list)
    const [todoPanelCollapsed, setTodoPanelCollapsed] = useState<boolean>(
        () => loadPrefs().todoPanelCollapsed ?? false,
    );
    const [showEvolution, setShowEvolution] = useState(false);

    // File tracker (Read/Write/Edit/Search tool paths during this session)
    const { files: trackedFiles, trackToolCall, clearFiles } = useFileTracker();

    // Responsive layout
    const breakpoint = useBreakpoint();
    const isMobile = breakpoint === 'xs' || breakpoint === 'sm';
    const isNarrow = isMobile || breakpoint === 'md';
    const [sidebarOpen, setSidebarOpen] = useState(false);

    // Narrow screen: nothing to force-close anymore (file tree lives in PrimarySidebar)

    const addToast = useCallback((type: ToastMessage['type'], message: string, duration?: number) => {
        const id = Math.random().toString(36).slice(2);
        setToasts(prev => [...prev, { id, type, message, duration }]);
    }, []);

    const dismissToast = useCallback((id: string) => {
        setToasts(prev => prev.filter(t => t.id !== id));
    }, []);

    // Forward-ref to approveTool — broken via ref to avoid the
    // useAgentEventHandler ↔ useAgentWebSocket circular dep.
    const approveToolRef = useRef<
        (
            sid: string,
            id: string,
            ok: boolean,
            reason?: string,
            editedArgs?: Record<string, unknown>,
        ) => void
    >(() => {});

    const handleEvent = useAgentEventHandler({
        assistantMsgRef,
        hasErrorRef,
        compactionTimerRef,
        setStreamingMsgId,
        setAgentPhase,
        setCurrentToolName,
        setIsCompacting,
        setShowSettings,
        setLoadingSessionId,
        addToast,
        refreshPersistedSessions,
        trackToolCall,
        approveTool: useCallback(
            (
                sid: string,
                id: string,
                ok: boolean,
                reason?: string,
                editedArgs?: Record<string, unknown>,
            ) => approveToolRef.current(sid, id, ok, reason, editedArgs),
            [],
        ),
    });

    const {
        isConnected,
        isThinking: wsThinking,
        connectionStatus,
        connect,
        disconnect,
        sendMessage,
        approveTool,
        stopAgent,
        createSession,
        switchSession,
    } = useAgentWebSocket(handleEvent);

    useEffect(() => {
        approveToolRef.current = approveTool;
    }, [approveTool]);

    // Override store's isThinking with WS state
    useEffect(() => {
        setThinking(wsThinking);
    }, [wsThinking, setThinking]);

    // Safety valve: if isThinking stays true for >6 minutes (server MAX_DURATION=5min + buffer),
    // reset it locally and notify the server to reset runningState.
    // Guards against AGENT_ERROR messages dropped by WebSocket transport errors.
    const thinkingStartRef = useRef<number | null>(null);
    useEffect(() => {
        if (isThinking) {
            if (!thinkingStartRef.current) thinkingStartRef.current = Date.now();
            const timer = setTimeout(() => {
                setThinking(false);
                setAgentPhase('thinking');
                addToast('warning', 'Agent response timed out. Please try again.');
                thinkingStartRef.current = null;
                // Notify server to reset runningState when WS is unavailable
                const sid = useSessionStore.getState().sessionId;
                if (sid) {
                    fetch(`/api/sessions/${sid}/cancel`, { method: 'POST' }).catch(() => {});
                }
            }, 6 * 60 * 1000); // 6 minutes
            return () => clearTimeout(timer);
        } else {
            thinkingStartRef.current = null;
        }
    }, [isThinking, setThinking, addToast]);

    // Toast on connection status changes — only after at least one successful connection
    useEffect(() => {
        if (connectionStatus === 'connected') {
            wasConnectedRef.current = true;
            setToasts(prev => prev.filter(t => t.type !== 'warning'));
        } else if (connectionStatus === 'disconnected' && wasConnectedRef.current) {
            addToast('warning', 'Connection lost. Reconnecting…', 0); // persistent
        }
    }, [connectionStatus, addToast]);

    // Load workspaces on mount; if none exist, force the create modal open
    useEffect(() => {
        refreshWorkspaces()
            .then((list) => {
                if (list.length === 0) {
                    setShowWorkspaceSettings({ open: true, workspaceId: null });
                }
            })
            .catch(() => {});
    }, [refreshWorkspaces]);

    // Load config on mount
    useEffect(() => {
        getConfig()
            .then((config) => {
                setCurrentModel(config.defaultModel);
                setServerConfig(config);
            })
            .catch(() => {
                // Backend not running yet
            });
    }, [setCurrentModel]);

    // Restore model from prefs if set
    useEffect(() => {
        if (prefs.model && !currentModel) {
            setCurrentModel(prefs.model);
        }
    }, []); // eslint-disable-line react-hooks/exhaustive-deps

    // Session restore: rehydrate the multi-tab chat layout from prefs first
    // (Cursor-style), falling back to the legacy single-session sessionStorage.
    useEffect(() => {
        const savedTabs = prefs.chatOpenTabs ?? [];
        const savedActive = prefs.chatActiveSession;
        if (savedTabs.length > 0) {
            const store = useSessionStore.getState();
            for (const sid of savedTabs) {
                store.openSession(sid);
                const cached = loadMessages(sid);
                if (cached.length > 0) {
                    store.setMessagesFor(sid, cached);
                }
            }
            const target = savedActive && savedTabs.includes(savedActive) ? savedActive : savedTabs[savedTabs.length - 1];
            if (target) store.setActiveSession(target);
            connect();
            return;
        }
        const savedId = sessionStorage.getItem('kairo-code-session-id');
        if (savedId) {
            const cached = loadMessages(savedId);
            if (cached.length > 0) {
                setSessionId(savedId);
                setMessages(cached);
            }
            connect();
        }
        // onConnect auto-detects the session and sends bind-session
    }, []); // eslint-disable-line react-hooks/exhaustive-deps

    // Persist chat tab layout (openTabs + activeSessionId) so a refresh
    // restores Cursor-style multi-tab chat state.
    useEffect(() => {
        const unsub = useSessionStore.subscribe((s, prev) => {
            if (s.openTabs !== prev.openTabs) {
                savePref('chatOpenTabs', s.openTabs);
            }
            if (s.activeSessionId !== prev.activeSessionId) {
                savePref('chatActiveSession', s.activeSessionId ?? undefined);
            }
        });
        return unsub;
    }, []);

    // Load the list of on-disk session snapshots once on mount.
    useEffect(() => { refreshPersistedSessions(); }, [refreshPersistedSessions]);

    // Cross-tab sync: when another tab creates or switches a session,
    // refresh the sidebar's persisted session list. Do not switch the
    // current tab's active session to avoid interrupting the user.
    useEffect(() => {
        const handler = (e: StorageEvent) => {
            if (e.key === 'kairo-last-session') {
                refreshPersistedSessions();
            }
        };
        window.addEventListener('storage', handler);
        return () => window.removeEventListener('storage', handler);
    }, [refreshPersistedSessions]);

    // Cross-tab restore: when the in-memory store is empty (e.g., new tab),
    // fall back to the last-active session id stored in localStorage and
    // rehydrate from the server-side snapshot.
    // Only run when the in-memory store is empty to avoid clobbering live state.
    useEffect(() => {
        if (sessionStorage.getItem('kairo-code-session-id')) return;
        if (useSessionStore.getState().messages.length > 0) return;
        const lastId = getLastSessionId();
        if (!lastId) return;
        loadSnapshot(lastId).then(snap => {
            if (snap && snap.messages.length > 0
                && useSessionStore.getState().messages.length === 0) {
                restoreSession(snap.sessionId, snap.messages, false);
                saveMessages(snap.sessionId, snap.messages);
            }
        });
    }, []); // eslint-disable-line react-hooks/exhaustive-deps

    // Snapshot messages to sessionStorage on change
    useEffect(() => {
        if (sessionId && messages.length > 0) {
            saveMessages(sessionId, messages);
        }
    }, [sessionId, messages]);

    const handleSend = useCallback(
        (text: string, image: AttachedImage | null) => {
            if (isMobile) setSidebarOpen(false);

            hasErrorRef.current = false;

            // Add user message to store
            addMessage({
                id: generateId(),
                role: 'user',
                content: text,
                toolCalls: [],
                timestamp: Date.now(),
                ...(image ? { imageData: image.data, imageMediaType: image.mediaType } : {}),
            });

            // Create session if needed
            if (!sessionId) {
                if (!currentWorkspaceId) {
                    addToast('warning', 'Create a workspace before starting a session.');
                    setShowWorkspaceSettings({ open: true, workspaceId: null });
                    return;
                }
                connect();
                createSession(currentWorkspaceId)
                    .then((newId) => {
                        setSessionId(newId);
                        // Auto-title from first user message via backend heuristic
                        autoNameSession(newId, text).then((name) => {
                            if (name) {
                                window.dispatchEvent(new Event('storage'));
                                refreshPersistedSessions();
                            }
                        });
                        sendMessage(newId, text, image?.data, image?.mediaType);
                    })
                    .catch((err) => {
                        console.error('[App] Failed to create session:', err);
                    });
            } else {
                // Auto-title from first user message via backend heuristic
                const userMsgCount = messages.filter(m => m.role === 'user').length;
                if (userMsgCount === 0) {
                    autoNameSession(sessionId, text).then((name) => {
                        if (name) {
                            window.dispatchEvent(new Event('storage'));
                            refreshPersistedSessions();
                        }
                    });
                }
                sendMessage(sessionId, text, image?.data, image?.mediaType);
            }
        },
        [sessionId, messages, currentModel, addMessage, setSessionId, connect, createSession, sendMessage, isMobile, refreshPersistedSessions, currentWorkspaceId, addToast],
    );

    const handleStop = useCallback(() => {
        if (sessionId) stopAgent(sessionId);
    }, [stopAgent, sessionId]);

    const handleInterruptAndSend = useCallback(
        (text: string, image: AttachedImage | null) => {
            if (isThinking) {
                handleStop();
                setTimeout(() => handleSend(text, image), 300);
            } else {
                handleSend(text, image);
            }
        },
        [isThinking, handleStop, handleSend],
    );

    const handleApproveTool = useCallback(
        (
            toolCallId: string,
            approved: boolean,
            reason?: string,
            editedArgs?: Record<string, unknown>,
        ) => {
            if (!sessionId) return;
            // Optimistically flip the card off 'pending' so a rapid second 'y' press picks the
            // NEXT pending card instead of re-firing approve for the one we just resolved.
            // Without this, the server logs "No pending approval for toolCallId: X" because the
            // first approve already removed it from pendingApprovals.
            const msgs = useSessionStore.getState().messages;
            const owner = msgs.find((m) => m.toolCalls.some((tc) => tc.id === toolCallId));
            if (owner) {
                useSessionStore.getState().updateToolCall(owner.id, toolCallId, {
                    status: approved ? 'approved' : 'rejected',
                });
            }
            approveTool(sessionId, toolCallId, approved, reason, editedArgs);
        },
        [approveTool, sessionId],
    );

    const handleRegenerate = useCallback((messageId: string) => {
        const msgs = useSessionStore.getState().messages;
        const idx = msgs.findIndex(m => m.id === messageId);
        if (idx < 0) return;
        const target = msgs[idx];
        const prevUser = [...msgs.slice(0, idx)].reverse().find(m => m.role === 'user');
        if (!prevUser || !sessionId) return;
        if (target.role === 'error') {
            // Drop the failed error + its triggering user bubble, then re-append a fresh user bubble
            // so the conversation reads [..., user, assistant] (not [..., user, error] left behind,
            // and not [...] with the new assistant orphaned without its prompt).
            const cleaned = msgs.filter(m => m.id !== target.id && m.id !== prevUser.id);
            const replay = {
                ...prevUser,
                id: generateId(),
                timestamp: Date.now(),
            };
            setMessages([...cleaned, replay]);
        }
        sendMessage(sessionId, prevUser.content);
    }, [sessionId, sendMessage, setMessages]);

    const handleEditResend = useCallback((messageId: string, newText: string) => {
        const msgs = useSessionStore.getState().messages;
        const updated = msgs.map(m => m.id === messageId ? { ...m, content: newText } : m);
        setMessages(updated);
        if (sessionId) sendMessage(sessionId, newText);
    }, [sessionId, sendMessage, setMessages]);

    // Track unread count when new messages arrive while not at bottom
    useEffect(() => {
        if (!atBottom && messages.length > prevMsgCount.current) {
            setUnreadCount(prev => prev + (messages.length - prevMsgCount.current));
        }
        prevMsgCount.current = messages.length;
    }, [messages.length, atBottom]);

    // Reset unread count when at bottom
    useEffect(() => {
        if (atBottom) setUnreadCount(0);
    }, [atBottom]);

    // Re-render every 60 seconds to update relative timestamps
    const messagesRef = useRef(messages);
    useEffect(() => {
        messagesRef.current = messages;
    }, [messages]);
    useEffect(() => {
        const interval = setInterval(() => {
            if (messagesRef.current.length > 0) {
                setMessages([...messagesRef.current]);
            }
        }, 60000);
        return () => clearInterval(interval);
    }, [messages.length]);

    const handleScrollToBottom = useCallback(() => {
        virtuosoRef.current?.scrollToIndex({ index: messages.length - 1, behavior: 'smooth' });
        setUnreadCount(0);
    }, [messages.length]);

    const handleScrollToPending = useCallback(() => {
        document.querySelector('[data-pending-tool]')?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }, []);

    const handleNewSession = useCallback(() => {
        if (sessionId) clearCachedMessages(sessionId);
        disconnect();
        setSessionId(null);
        clearMessages();
        assistantMsgRef.current = {};
        setStreamingMsgId(null);
        setAgentPhase('thinking');
        setCurrentToolName(undefined);
        setTokenUsage({ input: 0, output: 0 });
        setEstimatedCost(0);
        setExpandedMessages(new Set());
        clearFiles();
        sessionStorage.removeItem('kairo-code-session-id');
        clearLastSessionId();
    }, [disconnect, setSessionId, clearMessages, setTokenUsage, setEstimatedCost, sessionId, clearFiles]);

    const handleSelectSession = useCallback(
        async (id: string) => {
            if (id === sessionId) return;
            setLoadingSessionId(id);
            // Already open as a tab → just switch active, leave its messages intact.
            const alreadyOpen = useSessionStore.getState().openTabs.includes(id);
            if (alreadyOpen) {
                // IMMEDIATELY clear streaming state to prevent old-session events from
                // contaminating the new session's view during the transition window.
                streamingStore.clear(id);
                setStreamingMsgId(assistantMsgRef.current[id] ?? null);
                setAgentPhase('thinking');
                setCurrentToolName(undefined);
                useSessionStore.getState().setActiveSession(id);
                setLastSessionId(id);
                if (isConnected) switchSession(id);
                else connect();
                setLoadingSessionId(null);
                return;
            }
            // First time opening this session — open as a tab and hydrate from cache/snapshot.
            useSessionStore.getState().openSession(id);
            setExpandedMessages(new Set());
            clearFiles();
            const cached = loadMessages(id);
            if (cached.length > 0) {
                useSessionStore.getState().setMessagesFor(id, cached);
            } else {
                // Cache miss (different browser, cleared storage, etc.). Hydrate from
                // the server-side snapshot so we don't flash the WelcomeScreen while
                // waiting for SESSION_RESTORED. switchSession below will overlay any
                // newer in-memory state if the session is still live on the server.
                try {
                    const snap = await loadSnapshot(id);
                    if (snap && snap.messages.length > 0
                        && useSessionStore.getState().activeSessionId === id) {
                        useSessionStore.getState().setMessagesFor(id, snap.messages);
                        saveMessages(id, snap.messages);
                    }
                } catch {
                    // Snapshot fetch failure is non-fatal; SESSION_RESTORED will fill in.
                }
            }
            // Clear any stale streaming buffer for the destination before hydration.
            streamingStore.clear(id);
            assistantMsgRef.current[id] = null;
            setStreamingMsgId(null);
            setAgentPhase('thinking');
            setCurrentToolName(undefined);
            setLastSessionId(id);
            if (isConnected) {
                switchSession(id);
            } else {
                connect();
            }
        },
        [sessionId, isConnected, switchSession, connect, clearFiles],
    );

    // Restore a session from the on-disk snapshot (no live WebSocket bind).
    // Used for clicking entries in the sidebar's "History" section.
    const handleLoadSnapshotHistory = useCallback(
        async (id: string) => {
            if (id === sessionId) return;
            setLoadingSessionId(id);
            const snap = await loadSnapshot(id);
            if (!snap) {
                addToast('error', 'Snapshot not found on server');
                setLoadingSessionId(null);
                return;
            }
            disconnect();
            restoreSession(snap.sessionId, snap.messages, false);
            saveMessages(snap.sessionId, snap.messages);
            setLastSessionId(snap.sessionId);
            setExpandedMessages(new Set());
            clearFiles();
            assistantMsgRef.current = {};
            setStreamingMsgId(null);
            setAgentPhase('thinking');
            setCurrentToolName(undefined);
            setLoadingSessionId(null);
        },
        [sessionId, disconnect, restoreSession, addToast, clearFiles],
    );

    const handleDeleteSession = useCallback(
        (id: string) => {
            const name = getSessionName(id) ?? id.slice(0, 8);
            addToast('info', `Session deleted: ${name}`);
            clearCachedMessages(id);
            deleteSnapshot(id).then(() => refreshPersistedSessions());
            if (getLastSessionId() === id) clearLastSessionId();
            if (id === sessionId) {
                handleNewSession();
            }
        },
        [sessionId, handleNewSession, addToast, refreshPersistedSessions],
    );

    const handleCopyConversation = useCallback(async () => {
        if (messages.length === 0) return;
        const name = (sessionId ? getSessionName(sessionId) : null)
            ?? `session-${sessionId?.slice(0, 8) ?? 'new'}`;
        try {
            await copySessionToClipboard(messages, name);
            addToast('success', 'Conversation copied to clipboard');
        } catch {
            addToast('error', 'Failed to copy — clipboard access denied');
        }
    }, [messages, sessionId, addToast]);

    useGlobalShortcuts({
        sessionId,
        messages,
        sidebarSessions,
        sortedSessions,
        setShowMessageSearch,
        setShowSessionSearch,
        setShowCommandPalette,
        toggleShell: toggleBottomPanel,
        setShowShortcuts,
        handleNewSession,
        handleDeleteSession,
        handleSelectSession,
        handleCopyConversation,
        handleApproveTool,
        addToast,
    });

    const handleToggleTheme = useCallback(() => {
        // Theme toggling is handled by the Header component via DOM classList
        // Save the inverted theme preference
        savePref('theme', document.documentElement.classList.contains('dark') ? 'light' : 'dark');
    }, []);

    const handleOpenSettings = useCallback(() => setShowSettings(true), []);
    const handleCloseSettings = useCallback(() => setShowSettings(false), []);
    const [showMemoryEditor, setShowMemoryEditor] = useState(false);
    const handleOpenMemory = useCallback(() => setShowMemoryEditor(true), []);
    const [showPromptTemplates, setShowPromptTemplates] = useState(false);
    const handleInsertTemplate = useCallback((content: string) => {
        setChatInputAppend(content);
    }, []);
    const [shellCommandQueue, setShellCommandQueue] = useState<string[]>([]);
    const [shellExternalCommand, setShellExternalCommand] = useState<string>('');
    const [showMcpServers, setShowMcpServers] = useState(false);
    const handleOpenMcpServers = useCallback(() => setShowMcpServers(true), []);
    const handleCloseMcpServers = useCallback(() => setShowMcpServers(false), []);
    const [showHookConfig, setShowHookConfig] = useState(false);
    const [showToolStats, setShowToolStats] = useState(false);
    const handleCloseToolStats = useCallback(() => setShowToolStats(false), []);
    const [showTimeline, setShowTimeline] = useState(false);
    const [showBookmarks, setShowBookmarks] = useState(false);
    const [showTeamPanel, setShowTeamPanel] = useState(false);
    const [bookmarks, setBookmarks] = useState<Set<string>>(() =>
        sessionId ? new Set(getBookmarks(sessionId)) : new Set()
    );

    // Reset bookmarks when sessionId changes
    useEffect(() => {
        setBookmarks(sessionId ? new Set(getBookmarks(sessionId)) : new Set());
    }, [sessionId]);

    const handleToggleBookmark = useCallback((messageId: string) => {
        if (!sessionId) return;
        toggleBookmark(sessionId, messageId);
        setBookmarks(new Set(getBookmarks(sessionId)));
    }, [sessionId]);
    const handleSettingsSaved = useCallback((cfg: ServerConfig) => {
        setServerConfig(cfg);
        setCurrentModel(cfg.defaultModel);
        savePref('model', cfg.defaultModel);
    }, [setCurrentModel]);

    const handleOpenShortcuts = useCallback(() => setShowShortcuts(true), []);

    const handleMenuClick = useCallback(() => setSidebarOpen(v => !v), []);

    const handleModelChange = useCallback((m: string) => {
        setCurrentModel(m);
        savePref('model', m);
    }, [setCurrentModel]);

    const handleCloseSearch = useCallback(() => {
        setShowSearch(false);
        setSearchQuery('');
    }, []);

    // In-message search: sorted by original message order for navigation
    const sortedMessageSearchResults = useMemo(
        () => [...messageSearchResults].sort((a, b) => a.messageIndex - b.messageIndex),
        [messageSearchResults],
    );

    const handleMessageSearchQueryChange = useCallback((q: string) => {
        setMessageSearchQuery(q);
        const results = searchMessages(messages, q);
        setMessageSearchResults(results);
        setMessageSearchMatchIndex(0);
        if (results.length > 0) {
            const sorted = [...results].sort((a, b) => a.messageIndex - b.messageIndex);
            virtuosoRef.current?.scrollToIndex({ index: sorted[0].messageIndex, behavior: 'smooth' });
        }
    }, [messages]);

    const handleMessageSearchNext = useCallback(() => {
        if (sortedMessageSearchResults.length === 0) return;
        const next = (messageSearchMatchIndex + 1) % sortedMessageSearchResults.length;
        setMessageSearchMatchIndex(next);
        virtuosoRef.current?.scrollToIndex({ index: sortedMessageSearchResults[next].messageIndex, behavior: 'smooth' });
    }, [sortedMessageSearchResults, messageSearchMatchIndex]);

    const handleMessageSearchPrev = useCallback(() => {
        if (sortedMessageSearchResults.length === 0) return;
        const prev = (messageSearchMatchIndex - 1 + sortedMessageSearchResults.length) % sortedMessageSearchResults.length;
        setMessageSearchMatchIndex(prev);
        virtuosoRef.current?.scrollToIndex({ index: sortedMessageSearchResults[prev].messageIndex, behavior: 'smooth' });
    }, [sortedMessageSearchResults, messageSearchMatchIndex]);

    const handleCloseMessageSearch = useCallback(() => {
        setShowMessageSearch(false);
        setMessageSearchQuery('');
        setMessageSearchResults([]);
        setMessageSearchMatchIndex(0);
    }, []);

    const filteredMessages = useMemo(() => {
        if (!searchQuery.trim()) return messages;
        const q = searchQuery.toLowerCase();
        return messages.filter(m =>
            m.content?.toLowerCase().includes(q) ||
            m.toolCalls?.some(tc =>
                tc.toolName?.toLowerCase().includes(q) ||
                tc.result?.toLowerCase().includes(q)
            )
        );
    }, [messages, searchQuery]);

    const estimatedTokens = useMemo(() => estimateMessagesTokens(messages), [messages]);

    const pendingToolCount = useMemo(() => {
        let count = 0;
        for (const msg of messages) {
            for (const tc of msg.toolCalls ?? []) {
                if (tc.status === 'pending') count++;
            }
        }
        return count;
    }, [messages]);

    // Derive isRunning from agent state: true while thinking, processing tools, or writing
    const isRunning = isThinking || agentPhase === 'tool' || agentPhase === 'writing';

    // Browser notifications and title badge for agent state changes
    useAgentNotification({
        isRunning,
        pendingApprovalCount: pendingToolCount,
        hasError: hasErrorRef.current,
        isThinking: isThinking,
    });

    const currentDraft = useMemo(
        () => (sessionId ? loadDraft(sessionId) : ''),
        [sessionId],
    );

    const sessionStats = useMemo(() => {
        const userMessages = messages.filter(m => m.role === 'user').length;
        const assistantMessages = messages.filter(m => m.role === 'assistant').length;
        const toolCalls = messages.reduce((sum, m) => sum + (m.toolCalls?.length ?? 0), 0);
        return { userMessages, assistantMessages, toolCalls, estimatedTokens };
    }, [messages]);

    const handleExport = useCallback((format: 'markdown' | 'json') => {
        const name = (sessionId ? getSessionName(sessionId) : null) ?? `session-${sessionId?.slice(0, 8) ?? 'new'}`;
        exportAndDownload(messages, name, format);
    }, [messages, sessionId]);

    const handleExportSuccess = useCallback((format: 'markdown' | 'json') => {
        addToast('success', format === 'markdown' ? 'Exported as Markdown' : 'Exported as JSON');
    }, [addToast]);

    const handleInsertFile = useCallback((path: string, content: string, language: string) => {
        const block = `\`\`\`${language}
// ${path}
${content}
\`\`\`
`;
        setChatInputAppend(block);
    }, []);

    const handleInsertToChat = useCallback((text: string) => {
        setChatInputAppend(prev => (prev ? prev + '\n' + text : text));
    }, []);

    const handleMentionFile = useCallback((path: string) => {
        const mention = `@${path}`;
        setChatInputAppend(prev => prev ? `${prev} ${mention}` : mention);
    }, []);

    const handleApplyToFile = useCallback((filename: string, content: string) => {
        const instruction = `Please write the following content to \`${filename}\`:\n\`\`\`\n${content}\n\`\`\``;
        handleSend(instruction, null);
    }, [handleSend]);

    const handleRunCommand = useCallback((cmd: string) => {
        setShellCommandQueue(prev => [...prev, cmd]);
        if (!useLayoutStore.getState().bottomPanelOpen) toggleBottomPanel();
    }, [toggleBottomPanel]);

    const handleOpenFile = useCallback((path: string) => {
        openFileInEditor(path);
    }, [openFileInEditor]);

    // Process shell command queue: when BottomPanel is open and queue has items,
    // send the first command to the shell
    useEffect(() => {
        if (!bottomPanelOpen || shellCommandQueue.length === 0) return;
        const [cmd, ...rest] = shellCommandQueue;
        setShellExternalCommand(cmd);
        setShellCommandQueue(rest);
    }, [bottomPanelOpen, shellCommandQueue]);

    // Stable callbacks for SessionSidebar (prevents memo-busting re-renders)
    const handleCreateSession = useCallback(async (workspaceId: string) => {
        connect();
        const newId = await createSession(workspaceId);
        return { sessionId: newId };
    }, [connect, createSession]);

    const handleNewSessionFromSidebar = useCallback(({ sessionId: newId }: { sessionId: string }) => {
        // Open as a new tab so existing sessions stay alive in the background.
        useSessionStore.getState().openSession(newId);
        clearFiles();
        assistantMsgRef.current[newId] = null;
        if (isConnected) {
            switchSession(newId);
        } else {
            connect();
        }
    }, [isConnected, switchSession, connect, clearFiles]);

    /** ChatTabBar `+` button: spin up a session for the current workspace and open it. */
    const handleNewChatTab = useCallback(async () => {
        if (!currentWorkspaceId) return;
        connect();
        try {
            const newId = await createSession(currentWorkspaceId);
            useSessionStore.getState().openSession(newId);
            assistantMsgRef.current[newId] = null;
            if (isConnected) switchSession(newId);
        } catch (e) {
            addToast('error', e instanceof Error ? e.message : 'Failed to create session');
        }
    }, [currentWorkspaceId, connect, createSession, isConnected, switchSession, addToast]);

    // Command palette commands
    const commands: Command[] = useMemo(() => [
        {
            id: 'new-session',
            label: 'New Session',
            shortcut: '⌘N',
            icon: <Plus size={16} />,
            action: () => { handleNewSession(); setShowCommandPalette(false); },
        },
        {
            id: 'open-search',
            label: 'Search Messages',
            shortcut: '⌘F',
            icon: <Search size={16} />,
            action: () => { setShowSearch(v => !v); if (!showSearch) setSearchQuery(''); setShowCommandPalette(false); },
        },
        {
            id: 'session-search',
            label: 'Search All Sessions',
            shortcut: '⌘⇧F',
            icon: <Search size={14} />,
            action: () => { setShowSessionSearch(true); setShowCommandPalette(false); },
        },
        {
            id: 'toggle-file-tree',
            label: 'Toggle Files Sidebar',
            icon: <FolderTree size={16} />,
            action: () => { selectActivity('files'); setShowCommandPalette(false); },
        },
        {
            id: 'open-settings',
            label: 'Open Settings',
            icon: <Settings size={16} />,
            action: () => { handleOpenSettings(); setShowCommandPalette(false); },
        },
        {
            id: 'open-memory',
            label: 'Edit Memory Files (CLAUDE.md)',
            icon: <FileText size={16} />,
            action: () => { setShowMemoryEditor(true); setShowCommandPalette(false); },
        },
        {
            id: 'open-prompt-templates',
            label: 'Prompt Templates',
            description: 'Manage saved prompt templates',
            icon: <BookOpen size={16} />,
            action: () => { setShowPromptTemplates(true); setShowCommandPalette(false); },
        },
        {
            id: 'open-git-status',
            label: 'Git Changes',
            description: 'View modified files and diff',
            icon: <GitBranch size={16} />,
            action: () => { selectActivity('git'); setShowCommandPalette(false); },
        },
        {
            id: 'open-shell',
            label: 'Shell Terminal',
            description: 'Open interactive bash terminal',
            icon: <Terminal size={16} />,
            action: () => {
                if (!useLayoutStore.getState().bottomPanelOpen) toggleBottomPanel();
                setShowCommandPalette(false);
            },
        },
        {
            id: 'open-mcp-servers',
            label: 'MCP Servers',
            description: 'Manage MCP server configuration',
            icon: <Settings2 size={16} />,
            action: () => { handleOpenMcpServers(); setShowCommandPalette(false); },
        },
        {
            id: 'open-evolution',
            label: 'Open Evolution Lessons',
            description: 'View self-evolution learned lessons',
            icon: <Brain size={14} />,
            action: () => { setShowEvolution(true); setShowCommandPalette(false); },
        },
        {
            id: 'open-hooks',
            label: 'Configure Hooks',
            description: 'Enable/disable agent hooks',
            icon: <Zap size={14} />,
            action: () => { setShowHookConfig(true); setShowCommandPalette(false); },
        },
        {
            id: 'open-tool-stats',
            label: 'Tool Usage Stats',
            description: 'View per-tool usage statistics',
            icon: <Wrench size={16} />,
            action: () => { setShowToolStats(true); setShowCommandPalette(false); },
        },
        {
            id: 'open-timeline',
            label: 'Execution Timeline',
            description: 'View tool call timeline',
            icon: <Activity size={14} />,
            action: () => { setShowTimeline(true); setShowCommandPalette(false); },
        },
        {
            id: 'bookmarks',
            label: 'Bookmarks',
            description: 'View bookmarked messages',
            icon: <Star size={14} />,
            action: () => { setShowBookmarks(true); setShowCommandPalette(false); },
        },
        {
            id: 'teams',
            label: 'Teams',
            description: 'View active teams and tasks',
            icon: <Users size={14} />,
            action: () => { setShowTeamPanel(true); setShowCommandPalette(false); },
        },
        {
            id: 'toggle-theme',
            label: 'Toggle Theme',
            icon: <Moon size={16} />,
            action: () => { handleToggleTheme(); setShowCommandPalette(false); },
        },
        {
            id: 'show-shortcuts',
            label: 'Keyboard Shortcuts',
            shortcut: '?',
            icon: <HelpCircle size={16} />,
            action: () => { setShowShortcuts(true); setShowCommandPalette(false); },
        },
        {
            id: 'session-sort-newest',
            label: 'Sort sessions: Newest first',
            icon: <SortAsc size={16} />,
            action: () => { setSessionSortOrder('date-desc'); setShowCommandPalette(false); },
        },
        {
            id: 'session-sort-name',
            label: 'Sort sessions: A → Z',
            icon: <SortAsc size={16} />,
            action: () => { setSessionSortOrder('name-asc'); setShowCommandPalette(false); },
        },
        {
            id: 'session-prev',
            label: 'Previous session',
            shortcut: '⌘[',
            icon: <ArrowLeft size={14} />,
            action: () => {
                const idx = sortedSessions.findIndex(s => s.id === sessionId);
                if (idx > 0) { handleSelectSession(sortedSessions[idx - 1].id); setShowCommandPalette(false); }
            },
        },
        {
            id: 'session-next',
            label: 'Next session',
            shortcut: '⌘]',
            icon: <ArrowRight size={14} />,
            action: () => {
                const idx = sortedSessions.findIndex(s => s.id === sessionId);
                if (idx >= 0 && idx < sortedSessions.length - 1) { handleSelectSession(sortedSessions[idx + 1].id); setShowCommandPalette(false); }
            },
        },
        ...(messages.length > 0 ? [{
            id: 'export-chat',
            label: 'Export Chat',
            description: 'Download as Markdown',
            icon: <FileText size={16} />,
            action: () => { handleExport('markdown'); setShowCommandPalette(false); },
        }, {
            id: 'copy-conversation',
            label: 'Copy conversation as Markdown',
            icon: <Clipboard size={14} />,
            shortcut: '⌘⇧C',
            action: () => { handleCopyConversation(); setShowCommandPalette(false); },
        }] : []),
    ], [handleNewSession, handleOpenSettings, handleToggleTheme, handleExport, handleCopyConversation, handleOpenMcpServers, messages.length, sortedSessions, sessionId, handleSelectSession, showSearch, showSessionSearch, showEvolution, showHookConfig, setShowToolStats, showBookmarks, showTeamPanel, selectActivity, toggleBottomPanel]);

    return (
        <div className="h-screen flex flex-col bg-[var(--bg-primary)]">
            <Header
                currentModel={currentModel}
                tokenUsage={tokenUsage}
                estimatedCost={estimatedCost}
                contextWindow={getContextWindow(currentModel ?? '')}
                isCompacting={isCompacting}
                fileTrackerSlot={
                    <FileTrackerBadge files={trackedFiles} onClear={clearFiles} />
                }
                leadingSlot={
                    <div className="flex items-center gap-2">
                        <WorkspaceSwitcher
                            onCreate={() => setShowWorkspaceSettings({ open: true, workspaceId: null })}
                            onOpenSettings={(id) => setShowWorkspaceSettings({ open: true, workspaceId: id })}
                        />
                        {sessionId && getSessionName(sessionId) && (
                            <span
                                className="text-[11px] px-1.5 py-0.5 rounded bg-[var(--bg-hover)] text-[var(--text-muted)] truncate max-w-[200px]"
                                title={`Session: ${getSessionName(sessionId)}`}
                            >
                                {getSessionName(sessionId)}
                            </span>
                        )}
                    </div>
                }
                exportAction={
                    <ExportMenu
                        onExport={handleExport}
                        onExportSuccess={handleExportSuccess}
                        onCopy={handleCopyConversation}
                        disabled={messages.length === 0}
                    />
                }
                sessionStats={sessionStats}
                isThinking={isThinking}
                isToolRunning={agentPhase === 'tool'}
                isMobile={isMobile}
                onMenuClick={handleMenuClick}
                connectionStatus={sessionId ? connectionStatus : undefined}
            />

            <SearchBar
                isOpen={showSearch}
                query={searchQuery}
                onQueryChange={setSearchQuery}
                resultCount={filteredMessages.length}
                onClose={handleCloseSearch}
            />

            <div className="flex flex-1 overflow-hidden">
                {/* SessionSidebar: overlay on mobile, inline on desktop */}
                {isMobile ? (
                    <>
                        {sidebarOpen && (
                            <div
                                className="fixed inset-0 bg-black/40 z-30"
                                onClick={() => setSidebarOpen(false)}
                            />
                        )}
                        <div className={`fixed left-0 top-0 h-full z-40 transition-transform duration-200 ${
                            sidebarOpen ? 'translate-x-0' : '-translate-x-full'
                        }`}>
                            <SessionSidebar
                                activeSessionId={sessionId}
                                loadingSessionId={loadingSessionId}
                                onSelectSession={(id) => { handleSelectSession(id); setSidebarOpen(false); }}
                                onDeleteSession={handleDeleteSession}
                                onCreateSession={handleCreateSession}
                                onNewSession={handleNewSessionFromSidebar}
                                onSessionsChange={setSidebarSessions}
                                sortOrder={sessionSortOrder}
                                onSortChange={setSessionSortOrder}
                                persistedSessions={persistedSessions}
                                onLoadSnapshot={(id) => { handleLoadSnapshotHistory(id); setSidebarOpen(false); }}
                                onRenameSuccess={(_id, _name) => { refreshPersistedSessions(); }}
                                defaultWorkingDir={currentWorkspace?.workingDir}
                                currentWorkspaceId={currentWorkspaceId}
                                onCreateWorkspace={() => setShowWorkspaceSettings({ open: true, workspaceId: null })}
                            />
                        </div>
                    </>
                ) : (
                    <>
                    <ActivityBar />
                    {primarySidebarOpen && (
                        <>
                            <div
                                style={{ width: isNarrow ? Math.min(sidebarWidth, 260) : sidebarWidth }}
                                className="shrink-0 h-full"
                            >
                                <PrimarySidebar
                                    searchView={
                                        <SearchPanel
                                            embedded
                                            isOpen
                                            onClose={() => {}}
                                            onInsertResult={(text) => setChatInputAppend(text)}
                                            onOpenFile={(path, line) => openFileInEditor(path, line)}
                                            workspaceId={currentWorkspaceId ?? undefined}
                                        />
                                    }
                                    gitView={<GitStatusPanel embedded onClose={() => {}} />}
                                    workspacesView={
                                        <WorkspacesView
                                            onCreate={() => setShowWorkspaceSettings({ open: true, workspaceId: null })}
                                            onOpenSettings={(id) => setShowWorkspaceSettings({ open: true, workspaceId: id })}
                                        />
                                    }
                                    filesView={
                                        <FilesView
                                            workspaceId={currentWorkspaceId ?? undefined}
                                            rootKey={currentWorkspace?.workingDir}
                                            onInsertFile={handleInsertFile}
                                            onMentionFile={handleMentionFile}
                                        />
                                    }
                                />
                            </div>
                            <ResizeHandle
                                side="left"
                                width={sidebarWidth}
                                minWidth={200}
                                maxWidth={520}
                                onResize={setSidebarWidthStore}
                                onResizeEnd={(w) => setSidebarWidthStore(w)}
                            />
                        </>
                    )}
                    </>
                )}

                {/* Center: VS Code-style multi-tab editor area, falls back to welcome / chat-empty content. */}
                <EditorArea
                    workspaceId={currentWorkspaceId ?? undefined}
                    welcome={
                        messages.length === 0 && connectionStatus === 'connecting' ? (
                            <div className="flex-1 flex items-center justify-center text-[var(--text-muted)]">
                                <div className="text-sm">Connecting…</div>
                            </div>
                        ) : messages.length === 0 && isRunning ? (
                            <div className="flex-1 flex items-center justify-center">
                                <ThinkingIndicator isVisible={true} phase="thinking" thinkingText={thinkingText} />
                            </div>
                        ) : messages.length === 0 ? (
                            <WelcomeScreen
                                onSelectPrompt={(prompt) => handleSend(prompt, null)}
                                appVersion={__APP_VERSION__}
                                recentSessions={persistedSessions.slice(0, 5).map((s) => ({
                                    id: s.sessionId,
                                    name: s.name,
                                    updatedAt: s.savedAt,
                                }))}
                                onSelectSession={handleSelectSession}
                            />
                        ) : (
                            <div className="flex-1 flex items-center justify-center text-[var(--text-muted)] text-sm">
                                Open a file from the Explorer, or use the chat on the right.
                            </div>
                        )
                    }
                />

                {/* Right chat sidebar — Cursor-style two-column: Sessions strip | Chat tabs+messages+input */}
                {!isMobile && chatSidebarOpen && (
                    <>
                        <ResizeHandle
                            side="right"
                            width={chatWidth}
                            minWidth={420}
                            maxWidth={1100}
                            onResize={setChatWidthStore}
                            onResizeEnd={(w) => setChatWidthStore(w)}
                        />
                        <aside
                            className="relative flex flex-row h-full bg-[var(--bg-primary)] border-l border-[var(--border)] min-w-0"
                            style={{
                                flex: `0 1 ${isNarrow ? Math.min(chatWidth, 480) : chatWidth}px`,
                                minWidth: 360,
                            }}
                            aria-label="Chat sidebar"
                        >
                            {/* Embedded Sessions strip (Cursor-style: sessions live with chat) */}
                            {chatSessionsOpen && (
                                <>
                                    <div
                                        className="shrink-0 h-full border-r border-[var(--border)] overflow-hidden"
                                        style={{ width: isNarrow ? Math.min(chatSessionsWidth, 200) : chatSessionsWidth }}
                                    >
                                        <SessionSidebar
                                            embedded
                                            activeSessionId={sessionId}
                                            loadingSessionId={loadingSessionId}
                                            onSelectSession={handleSelectSession}
                                            onDeleteSession={handleDeleteSession}
                                            onCreateSession={handleCreateSession}
                                            onNewSession={handleNewSessionFromSidebar}
                                            onSessionsChange={setSidebarSessions}
                                            sortOrder={sessionSortOrder}
                                            onSortChange={setSessionSortOrder}
                                            persistedSessions={persistedSessions}
                                            onLoadSnapshot={handleLoadSnapshotHistory}
                                            onRenameSuccess={(_id, _name) => { refreshPersistedSessions(); }}
                                            defaultWorkingDir={currentWorkspace?.workingDir}
                                            currentWorkspaceId={currentWorkspaceId}
                                            onCreateWorkspace={() => setShowWorkspaceSettings({ open: true, workspaceId: null })}
                                        />
                                    </div>
                                    <ResizeHandle
                                        side="left"
                                        width={chatSessionsWidth}
                                        minWidth={180}
                                        maxWidth={380}
                                        onResize={setChatSessionsWidthStore}
                                        onResizeEnd={(w) => setChatSessionsWidthStore(w)}
                                    />
                                </>
                            )}
                            <div className="relative flex flex-col h-full flex-1 min-w-0">
                            {/* Multi-session tab bar (Cursor-style) — always rendered so the
                                Sessions-panel toggle stays reachable even with no open tabs. */}
                            <ChatTabBar
                                onNew={handleNewChatTab}
                                onActivate={(sid) => {
                                    if (isConnected) switchSession(sid);
                                    else connect();
                                }}
                            />
                            <TodoListPanel
                                todos={todos}
                                collapsed={todoPanelCollapsed}
                                onToggleCollapse={() => setTodoPanelCollapsed((v) => !v)}
                            />
                            {messages.length === 0 && connectionStatus === 'connecting' ? (
                                <div className="flex-1 flex items-center justify-center text-[var(--text-muted)]">
                                    <div className="text-sm">Connecting…</div>
                                </div>
                            ) : messages.length === 0 && isRunning ? (
                                <div className="flex-1 flex items-center justify-center">
                                    <ThinkingIndicator isVisible={true} phase="thinking" thinkingText={thinkingText} />
                                </div>
                            ) : messages.length === 0 ? (
                                <div className="flex-1 flex items-center justify-center px-4 text-center text-[var(--text-muted)] text-sm">
                                    Send a message to start a session.
                                </div>
                            ) : (
                                <>
                                    {showMessageSearch && (
                                        <MessageSearchBar
                                            onQueryChange={handleMessageSearchQueryChange}
                                            onClose={handleCloseMessageSearch}
                                            matchCount={sortedMessageSearchResults.length}
                                            currentMatch={sortedMessageSearchResults.length > 0 ? messageSearchMatchIndex + 1 : 0}
                                            onPrev={handleMessageSearchPrev}
                                            onNext={handleMessageSearchNext}
                                        />
                                    )}
                                    <ErrorBoundary>
                                        <Virtuoso
                                            ref={virtuosoRef}
                                            className="flex-1 pt-6 pb-3 overflow-x-hidden [scrollbar-gutter:stable]"
                                            data={filteredMessages}
                                            followOutput={(isAtBottom) => {
                                                if ((showSearch && searchQuery) || (showMessageSearch && messageSearchQuery)) return false;
                                                return isAtBottom ? 'auto' : false;
                                            }}
                                            atBottomStateChange={(bottom) => setAtBottom(bottom)}
                                            atBottomThreshold={48}
                                            itemContent={(index, msg) => {
                                                const msgObj = msg as Message;
                                                const prevMsg = index > 0 ? (filteredMessages[index - 1] as Message) : null;
                                                const showDateSep = prevMsg && prevMsg.timestamp && msgObj.timestamp &&
                                                    new Date(msgObj.timestamp).toDateString() !== new Date(prevMsg.timestamp).toDateString();
                                                const dateLabel = msgObj.timestamp ? new Date(msgObj.timestamp).toLocaleDateString([], { weekday: 'long', month: 'short', day: 'numeric' }) : '';
                                                const currentMatchMsgIdx = sortedMessageSearchResults[messageSearchMatchIndex]?.messageIndex;
                                                const isSearchMatch = messageSearchQuery.length > 0 && sortedMessageSearchResults.some(r => r.messageIndex === index);
                                                const isCurrentMatch = index === currentMatchMsgIdx;
                                                const shouldCollapse = msgObj.role === 'assistant' && isCollapsible(msgObj.content ?? '');
                                                const isExpanded = expandedMessages.has(msgObj.id);
                                                const isStreaming = msgObj.id === streamingMsgId;
                                                return (
                                                    <div className="px-4">
                                                        {showDateSep && (
                                                            <div className="flex items-center gap-3 px-2 py-2">
                                                                <div className="flex-1 h-px bg-[var(--border)]" />
                                                                <span className="text-xs text-[var(--text-muted)]">{dateLabel}</span>
                                                                <div className="flex-1 h-px bg-[var(--border)]" />
                                                            </div>
                                                        )}
                                                        <ChatMessage
                                                            message={msgObj}
                                                            onApproveTool={handleApproveTool}
                                                            isStreaming={isStreaming}
                                                            sessionId={sessionId ?? undefined}
                                                            onRegenerate={handleRegenerate}
                                                            onEditResend={handleEditResend}
                                                            onInsertToChat={handleInsertToChat}
                                                            onApplyToFile={handleApplyToFile}
                                                            onRetry={msgObj.role === 'error' ? () => handleRegenerate(msgObj.id) : undefined}
                                                            searchHighlight={isSearchMatch}
                                                            isCurrentMatch={isCurrentMatch}
                                                            isCollapsed={shouldCollapse && !isExpanded}
                                                            onToggleCollapse={shouldCollapse && !isStreaming ? () => handleToggleMessageExpand(msgObj.id) : undefined}
                                                            isBookmarked={bookmarks.has(msgObj.id)}
                                                            onToggleBookmark={handleToggleBookmark}
                                                            onRunCommand={handleRunCommand}
                                                            onOpenFile={handleOpenFile}
                                                        />
                                                    </div>
                                                );
                                            }}
                                            components={{
                                                Footer: () =>
                                                    isRunning ? (
                                                        <div>
                                                            <ThinkingIndicator
                                                                isVisible={true}
                                                                phase={agentPhase}
                                                                toolName={currentToolName}
                                                                toolElapsed={toolElapsed}
                                                                thinkingText={thinkingText}
                                                            />
                                                            {lastTool && (
                                                                <LastToolDisplay name={lastTool.name} elapsed={lastTool.elapsed} />
                                                            )}
                                                        </div>
                                                    ) : null,
                                            }}
                                        />
                                    </ErrorBoundary>

                                    {!atBottom && (
                                        <div className="absolute bottom-20 right-4 z-10">
                                            <button
                                                onClick={handleScrollToBottom}
                                                className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium
                                                    bg-[var(--bg-secondary)] border border-[var(--border)] rounded-full
                                                    shadow-lg text-[var(--text-secondary)] hover:text-[var(--text-primary)]
                                                    hover:bg-[var(--bg-hover)] transition-all"
                                                aria-label="Scroll to bottom"
                                            >
                                                <ArrowDown size={12} />
                                                <span>Jump to latest</span>
                                                {unreadCount > 0 && (
                                                    <span className="ml-1 px-1.5 py-0.5 text-[10px] font-bold rounded-full bg-[var(--color-primary)] text-white">
                                                        {unreadCount > 9 ? '9+' : unreadCount}
                                                    </span>
                                                )}
                                            </button>
                                        </div>
                                    )}
                                </>
                            )}

                            {sessionId && !isConnected && wasConnectedRef.current && (
                                <div className="px-4 py-1 text-xs text-center bg-[var(--color-warning-bg)] text-[var(--color-warning)]">
                                    Reconnecting...
                                </div>
                            )}

                            <PendingApprovalBanner
                                count={pendingToolCount}
                                onScrollToPending={handleScrollToPending}
                            />
                            <ChatInput
                                key={sessionId ?? 'no-session'}
                                sessionId={sessionId ?? undefined}
                                initialDraft={currentDraft}
                                onSend={handleSend}
                                onInterruptAndSend={handleInterruptAndSend}
                                onStop={handleStop}
                                disabled={false}
                                isThinking={isThinking}
                                running={running}
                                appendText={chatInputAppend}
                                onAppendConsumed={() => setChatInputAppend('')}
                                pendingToolCount={pendingToolCount}
                                onScrollToPending={handleScrollToPending}
                                models={serverConfig?.availableModels ?? []}
                                currentModel={currentModel}
                                onModelChange={handleModelChange}
                                tokenUsage={tokenUsage}
                                contextWindow={getContextWindow(currentModel ?? '')}
                                isCompacting={isCompacting}
                                onNewChat={handleNewChatTab}
                            />
                            </div>
                        </aside>
                    </>
                )}
            </div>

            {!isMobile && bottomPanelOpen && (
                <>
                    <ResizeHandle
                        side="bottom"
                        orientation="horizontal"
                        width={bottomHeight}
                        minWidth={120}
                        maxWidth={800}
                        onResize={setBottomHeight}
                        onResizeEnd={(h) => setBottomHeight(h)}
                    />
                    <div style={{ height: bottomHeight }} className="shrink-0">
                        <BottomPanel
                            workspaceId={currentWorkspaceId ?? undefined}
                            externalCommand={shellExternalCommand}
                        />
                    </div>
                </>
            )}

            <StatusBar
                connectionStatus={sessionId ? connectionStatus : undefined}
                currentModel={currentModel ?? undefined}
                onOpenSettings={handleOpenSettings}
                onOpenMemory={handleOpenMemory}
                onOpenShortcuts={handleOpenShortcuts}
                onToggleTheme={handleToggleTheme}
            />

            {showSettings && serverConfig && (
                <SettingsModal
                    isOpen={showSettings}
                    onClose={handleCloseSettings}
                    config={serverConfig}
                    onSaved={handleSettingsSaved}
                    onOpenMcpServers={handleOpenMcpServers}
                />
            )}

            {showWorkspaceSettings.open && (
                <WorkspaceSettingsModal
                    isOpen={showWorkspaceSettings.open}
                    onClose={() => setShowWorkspaceSettings({ open: false, workspaceId: null })}
                    workspaceId={showWorkspaceSettings.workspaceId}
                />
            )}

            <SearchPanel
                isOpen={showSearch}
                onClose={() => setShowSearch(false)}
                onInsertResult={(text) => setChatInputAppend(text)}
                onOpenFile={(path, line) => openFileInEditor(path, line)}
                workspaceId={currentWorkspaceId ?? undefined}
            />

            <CommandPalette
                isOpen={showCommandPalette}
                onClose={() => setShowCommandPalette(false)}
                commands={commands}
            />

            <ShortcutsModal isOpen={showShortcuts} onClose={() => setShowShortcuts(false)} />

            <ToastContainer toasts={toasts} onDismiss={dismissToast} />

            {showMemoryEditor && currentWorkspace && (
                <MemoryEditorPanel
                    workingDir={currentWorkspace.workingDir}
                    onClose={() => setShowMemoryEditor(false)}
                />
            )}

            {showPromptTemplates && (
                <PromptTemplatesPanel
                    onClose={() => setShowPromptTemplates(false)}
                    onInsert={handleInsertTemplate}
                />
            )}
            {showMcpServers && (
                <McpServersPanel onClose={handleCloseMcpServers} />
            )}
            {showHookConfig && (
                <HookConfigPanel onClose={() => setShowHookConfig(false)} />
            )}
            {showToolStats && (
                <ToolStatsDashboard
                    sessionId={sessionId}
                    onClose={handleCloseToolStats}
                />
            )}
            {showEvolution && <EvolutionPanel onClose={() => setShowEvolution(false)} />}
            {showSessionSearch && (
                <SessionSearchPanel
                    isOpen={showSessionSearch}
                    onClose={() => setShowSessionSearch(false)}
                    onSelectSession={handleSelectSession}
                />
            )}
            {showTimeline && (
                <ExecutionTimeline sessionId={sessionId} onClose={() => setShowTimeline(false)} />
            )}
            {showBookmarks && sessionId && (
                <BookmarkPanel
                    sessionId={sessionId}
                    messages={messages}
                    isOpen={showBookmarks}
                    onClose={() => setShowBookmarks(false)}
                    onScrollToMessage={(messageId) => {
                        const idx = messages.findIndex(m => m.id === messageId);
                        if (idx >= 0) {
                            virtuosoRef.current?.scrollToIndex({ index: idx, behavior: 'smooth' });
                            setShowBookmarks(false);
                        }
                    }}
                />
            )}
            {showTeamPanel && (
                <TeamPanel isOpen={showTeamPanel} onClose={() => setShowTeamPanel(false)} />
            )}
            <DevDiagnosticsPanel />
        </div>
    );
}

export default App;
