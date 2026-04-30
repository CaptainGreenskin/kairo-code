import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ArrowDown, Plus, Search, FolderTree, Settings, Moon, HelpCircle, FileText, Clipboard, SortAsc, ArrowLeft, ArrowRight, BookOpen } from 'lucide-react';
import { useSessionStore } from '@store/sessionStore';
import { streamingStore } from '@store/streamingStore';
import { useAgentWebSocket } from '@hooks/useAgentWebSocket';
import { useBreakpoint } from '@hooks/useBreakpoint';
import { Header } from '@components/Header';
import { SearchBar } from '@components/SearchBar';
import { ChatMessage } from '@components/ChatMessage';
import { ThinkingIndicator } from '@components/ThinkingIndicator';
import { LastToolDisplay } from '@components/LastToolDisplay';
import type { Phase } from '@components/ThinkingIndicator';
import { ChatInput } from '@components/ChatInput';
import { SessionSidebar } from '@components/SessionSidebar';
import { SettingsModal } from '@components/SettingsModal';
import { FileTreePanel } from '@components/FileTreePanel';
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
import { ExportMenu } from '@components/ExportMenu';
import type { AgentEvent, ToolCall, Message, ServerConfig } from '@/types/agent';
import { getConfig } from '@api/config';
import { exportAndDownload, copySessionToClipboard } from '@utils/exportSession';
import { estimateMessagesTokens } from '@utils/tokenCount';
import { searchMessages } from '@utils/messageSearch';
import type { MessageSearchResult } from '@utils/messageSearch';
import { Virtuoso } from 'react-virtuoso';
import { saveMessages, loadMessages, clearMessages as clearCachedMessages } from '@utils/messageCache';
import { setSessionName, getSessionName } from '@utils/sessionNames';
import { loadPrefs, savePref } from '@utils/userPrefs';
import { loadDraft } from '@utils/inputDraft';
import { isCollapsible } from '@utils/messageCollapse';
import { sortSessions, type SessionSortOrder } from '@utils/sessionSort';

declare const __APP_VERSION__: string;

function generateId(): string {
    return crypto.randomUUID();
}

function App() {
    const prefs = loadPrefs();

    const {
        sessionId,
        messages,
        isThinking,
        tokenUsage,
        estimatedCost,
        currentModel,
        setSessionId,
        addMessage,
        setMessages,
        addToolCall,
        updateToolCall,
        setThinking,
        setTokenUsage,
        setEstimatedCost,
        setCurrentModel,
        clearMessages,
        restoreSession,
    } = useSessionStore();

    const assistantMsgRef = useRef<string | null>(null);
    const [streamingMsgId, setStreamingMsgId] = useState<string | null>(null);
    const [agentPhase, setAgentPhase] = useState<Phase>('thinking');
    const [currentToolName, setCurrentToolName] = useState<string | undefined>(undefined);

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
    const [serverConfig, setServerConfig] = useState<ServerConfig | null>(null);
    const [fileTreeOpen, setFileTreeOpen] = useState(() => prefs.fileTreeOpen ?? false);
    const [showSearch, setShowSearch] = useState(false);
    const [searchQuery, setSearchQuery] = useState('');
    const [showMessageSearch, setShowMessageSearch] = useState(false);
    const [messageSearchQuery, setMessageSearchQuery] = useState('');
    const [messageSearchResults, setMessageSearchResults] = useState<MessageSearchResult[]>([]);
    const [messageSearchMatchIndex, setMessageSearchMatchIndex] = useState(0);
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
    const [sessionSortOrder, setSessionSortOrder] = useState<SessionSortOrder>('date-desc');
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

    // Responsive layout
    const breakpoint = useBreakpoint();
    const isMobile = breakpoint === 'xs' || breakpoint === 'sm';
    const isNarrow = isMobile || breakpoint === 'md';
    const [sidebarOpen, setSidebarOpen] = useState(false);

    // Narrow screen: force close FileTreePanel
    useEffect(() => {
        if (isNarrow && fileTreeOpen) setFileTreeOpen(false);
    }, [isNarrow]);

    const addToast = useCallback((type: ToastMessage['type'], message: string, duration?: number) => {
        const id = Math.random().toString(36).slice(2);
        setToasts(prev => [...prev, { id, type, message, duration }]);
    }, []);

    const dismissToast = useCallback((id: string) => {
        setToasts(prev => prev.filter(t => t.id !== id));
    }, []);

    const handleEvent = useCallback(
        (event: AgentEvent) => {
            switch (event.type) {
                case 'TEXT_CHUNK': {
                    const text = (event.payload as { text: string }).text;
                    setAgentPhase('writing');
                    setCurrentToolName(undefined);
                    if (!assistantMsgRef.current) {
                        const msgId = generateId();
                        assistantMsgRef.current = msgId;
                        setStreamingMsgId(msgId);
                        // Create initial message in sessionStore
                        addMessage({
                            id: msgId,
                            role: 'assistant',
                            content: '',
                            toolCalls: [],
                            timestamp: Date.now(),
                        });
                    }
                    // Stream to external store (bypasses Immer)
                    streamingStore.append(event.sessionId, text);
                    break;
                }

                case 'TOOL_CALL': {
                    const payload = event.payload as {
                        toolCallId: string;
                        toolName: string;
                        input: Record<string, unknown>;
                        requiresApproval: boolean;
                    };
                    setAgentPhase('tool');
                    setCurrentToolName(payload.toolName);
                    if (!assistantMsgRef.current) {
                        const msgId = generateId();
                        assistantMsgRef.current = msgId;
                        addMessage({
                            id: msgId,
                            role: 'assistant',
                            content: '',
                            toolCalls: [],
                            timestamp: Date.now(),
                        });
                    }
                    const toolCall: ToolCall = {
                        id: payload.toolCallId,
                        toolName: payload.toolName,
                        input: payload.input,
                        status: payload.requiresApproval ? 'pending' : 'approved',
                        requiresApproval: payload.requiresApproval,
                    };
                    addToolCall(assistantMsgRef.current, toolCall);
                    break;
                }

                case 'TOOL_RESULT': {
                    const payload = event.payload as {
                        toolCallId: string;
                        result: string;
                        isError: boolean;
                        durationMs: number;
                    };
                    setAgentPhase('thinking');
                    setCurrentToolName(undefined);
                    // Find the message containing this tool call
                    const store = useSessionStore.getState();
                    const targetMsg = store.messages.find((m) =>
                        m.toolCalls.some((tc) => tc.id === payload.toolCallId),
                    );
                    if (targetMsg) {
                        updateToolCall(targetMsg.id, payload.toolCallId, {
                            result: payload.result,
                            status: 'done',
                            durationMs: payload.durationMs,
                            isError: payload.isError,
                        });
                    }
                    break;
                }

                case 'AGENT_DONE': {
                    const payload = event.payload as {
                        inputTokens: number;
                        outputTokens: number;
                    };
                    // Flush streaming content to sessionStore
                    if (assistantMsgRef.current) {
                        const content = streamingStore.getContent(event.sessionId);
                        if (content) {
                            setMessages(
                                useSessionStore.getState().messages.map((m) =>
                                    m.id === assistantMsgRef.current
                                        ? { ...m, content }
                                        : m,
                                ),
                            );
                        }
                        streamingStore.clear(event.sessionId);
                    }
                    assistantMsgRef.current = null;
                    setStreamingMsgId(null);
                    setThinking(false);
                    setAgentPhase('thinking');
                    setCurrentToolName(undefined);
                    setTokenUsage({
                        input: payload.inputTokens,
                        output: payload.outputTokens,
                    });
                    // Rough cost estimate (placeholder rates)
                    const cost =
                        (payload.inputTokens * 0.001 + payload.outputTokens * 0.003) /
                        1000;
                    setEstimatedCost(cost);
                    break;
                }

                case 'AGENT_ERROR': {
                    const payload = event.payload as { message: string };
                    assistantMsgRef.current = null;
                    setStreamingMsgId(null);
                    setThinking(false);
                    setAgentPhase('thinking');
                    setCurrentToolName(undefined);
                    setLoadingSessionId(null);
                    addToast('error', payload.message);
                    addMessage({
                        id: generateId(),
                        role: 'error',
                        content: payload.message,
                        toolCalls: [],
                        timestamp: Date.now(),
                    });
                    break;
                }

                case 'AGENT_THINKING': {
                    const payload = event.payload as { isThinking: boolean };
                    setThinking(payload.isThinking);
                    if (payload.isThinking) {
                        assistantMsgRef.current = null;
                        setStreamingMsgId(null);
                        setAgentPhase('thinking');
                        setCurrentToolName(undefined);
                    }
                    break;
                }

                case 'SESSION_RESTORED': {
                    const payload = event.payload as { messages: Message[]; running: boolean };
                    restoreSession(event.sessionId, payload.messages, payload.running);
                    // Clear streaming store for this session
                    streamingStore.clear(event.sessionId);
                    setLoadingSessionId(null);
                    break;
                }
            }
        },
        [
            addMessage,
            setMessages,
            addToolCall,
            updateToolCall,
            setThinking,
            setTokenUsage,
            setEstimatedCost,
            restoreSession,
            setLoadingSessionId,
            addToast,
        ],
    );

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
    } = useAgentWebSocket(handleEvent);

    // Override store's isThinking with WS state
    useEffect(() => {
        setThinking(wsThinking);
    }, [wsThinking, setThinking]);

    // Toast on connection status changes
    useEffect(() => {
        if (connectionStatus === 'disconnected') {
            addToast('warning', 'Connection lost. Reconnecting…', 0); // persistent
        } else if (connectionStatus === 'connected') {
            setToasts(prev => prev.filter(t => t.type !== 'warning'));
        }
    }, [connectionStatus, addToast]);

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

    // Session restore: if sessionStorage has a sessionId, reconnect and auto-bind
    useEffect(() => {
        const savedId = sessionStorage.getItem('kairo-code-session-id');
        if (savedId) {
            // 立即从缓存恢复，让用户看到消息，不用等 WebSocket
            const cached = loadMessages(savedId);
            if (cached.length > 0) {
                setSessionId(savedId);
                setMessages(cached);
            }
            connect();
        }
        // onConnect auto-detects the session and sends bind-session
    }, []); // eslint-disable-line react-hooks/exhaustive-deps

    // Snapshot messages to sessionStorage on change
    useEffect(() => {
        if (sessionId && messages.length > 0) {
            saveMessages(sessionId, messages);
        }
    }, [sessionId, messages]);

    // Global keyboard shortcut: Cmd+F opens in-message search, Cmd+Shift+F opens workspace search
    useEffect(() => {
        const handler = (e: KeyboardEvent) => {
            if ((e.metaKey || e.ctrlKey) && !e.shiftKey && e.key === 'f') {
                const tag = (e.target as HTMLElement).tagName;
                if (tag !== 'INPUT' && tag !== 'TEXTAREA') {
                    e.preventDefault();
                    setShowMessageSearch(v => !v);
                }
            }
            if ((e.metaKey || e.ctrlKey) && e.shiftKey && e.key === 'f') {
                e.preventDefault();
                setShowSearch(v => {
                    if (!v) setSearchQuery('');
                    return !v;
                });
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, []);

    // Global keyboard shortcut: Cmd+K opens command palette
    useEffect(() => {
        const handler = (e: KeyboardEvent) => {
            if ((e.metaKey || e.ctrlKey) && !e.shiftKey && e.key === 'k') {
                e.preventDefault();
                setShowCommandPalette(prev => !prev);
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, []);

    // Global keyboard shortcut: ? opens shortcuts modal
    useEffect(() => {
        const handler = (e: KeyboardEvent) => {
            const tag = (e.target as HTMLElement).tagName;
            if (tag === 'INPUT' || tag === 'TEXTAREA') return;
            if (e.key === '?') {
                e.preventDefault();
                setShowShortcuts(prev => !prev);
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, []);

    const handleSend = useCallback(
        (text: string) => {
            if (isMobile) setSidebarOpen(false);

            // Add user message to store
            addMessage({
                id: generateId(),
                role: 'user',
                content: text,
                toolCalls: [],
                timestamp: Date.now(),
            });

            // Create session if needed
            if (!sessionId) {
                connect();
                createSession('.', currentModel || 'gpt-4')
                    .then((newId) => {
                        setSessionId(newId);
                        // Auto-title: set session name from first user message if not yet named
                        if (!getSessionName(newId)) {
                            const autoTitle = text.trim().slice(0, 40).replace(/\n/g, ' ');
                            if (autoTitle.length > 0) setSessionName(newId, autoTitle);
                        }
                        sendMessage(newId, text);
                    })
                    .catch((err) => {
                        console.error('[App] Failed to create session:', err);
                    });
            } else {
                // Auto-title: set session name from first user message if not yet named
                const userMsgCount = messages.filter(m => m.role === 'user').length;
                if (userMsgCount === 0 && !getSessionName(sessionId)) {
                    const autoTitle = text.trim().slice(0, 40).replace(/\n/g, ' ');
                    if (autoTitle.length > 0) setSessionName(sessionId, autoTitle);
                }
                sendMessage(sessionId, text);
            }
        },
        [sessionId, messages, currentModel, addMessage, setSessionId, connect, createSession, sendMessage, isMobile],
    );

    const handleStop = useCallback(() => {
        if (sessionId) stopAgent(sessionId);
    }, [stopAgent, sessionId]);

    const handleInterruptAndSend = useCallback(
        (text: string) => {
            if (isThinking) {
                handleStop();
                setTimeout(() => handleSend(text), 300);
            } else {
                handleSend(text);
            }
        },
        [isThinking, handleStop, handleSend],
    );

    const handleApproveTool = useCallback(
        (toolCallId: string, approved: boolean) => {
            if (sessionId) approveTool(sessionId, toolCallId, approved);
        },
        [approveTool, sessionId],
    );

    const handleRegenerate = useCallback((messageId: string) => {
        const msgs = useSessionStore.getState().messages;
        const idx = msgs.findIndex(m => m.id === messageId);
        const prevUser = [...msgs.slice(0, idx)].reverse().find(m => m.role === 'user');
        if (!prevUser || !sessionId) return;
        sendMessage(sessionId, prevUser.content);
    }, [sessionId, sendMessage]);

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
        assistantMsgRef.current = null;
        setStreamingMsgId(null);
        setAgentPhase('thinking');
        setCurrentToolName(undefined);
        setTokenUsage({ input: 0, output: 0 });
        setEstimatedCost(0);
        setExpandedMessages(new Set());
        sessionStorage.removeItem('kairo-code-session-id');
    }, [disconnect, setSessionId, clearMessages, setTokenUsage, setEstimatedCost, sessionId]);

    const handleSelectSession = useCallback(
        (id: string) => {
            if (id === sessionId) return;
            setLoadingSessionId(id);
            disconnect();
            setSessionId(id);
            setExpandedMessages(new Set());
            // 立即从缓存恢复
            const cached = loadMessages(id);
            if (cached.length > 0) {
                setMessages(cached);
            } else {
                clearMessages();
            }
            assistantMsgRef.current = null;
            setStreamingMsgId(null);
            setAgentPhase('thinking');
            setCurrentToolName(undefined);
            connect();
        },
        [sessionId, disconnect, setSessionId, clearMessages, connect],
    );

    const handleDeleteSession = useCallback(
        (id: string) => {
            const name = getSessionName(id) ?? id.slice(0, 8);
            addToast('info', `Session deleted: ${name}`);
            clearCachedMessages(id);
            if (id === sessionId) {
                handleNewSession();
            }
        },
        [sessionId, handleNewSession, addToast],
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

    // Global keyboard shortcut: Cmd+N new session, Cmd+W close session, Cmd+1-9 switch session
    useEffect(() => {
        const handler = (e: KeyboardEvent) => {
            const tag = (e.target as HTMLElement).tagName;
            if (tag === 'INPUT' || tag === 'TEXTAREA') return;

            // Cmd+N — new session (do not block Shift+Cmd+N for browser incognito)
            if ((e.metaKey || e.ctrlKey) && e.key === 'n' && !e.shiftKey) {
                e.preventDefault();
                handleNewSession();
                return;
            }

            // Cmd+W — close current session
            if ((e.metaKey || e.ctrlKey) && e.key === 'w' && sessionId) {
                e.preventDefault();
                handleDeleteSession(sessionId);
                return;
            }

            // Cmd+1-9 — switch to session N (1-indexed)
            if ((e.metaKey || e.ctrlKey) && /^[1-9]$/.test(e.key)) {
                const idx = parseInt(e.key) - 1;
                if (idx < sidebarSessions.length) {
                    e.preventDefault();
                    handleSelectSession(sidebarSessions[idx].sessionId);
                }
            }

            // Cmd+[ — switch to previous session
            if ((e.metaKey || e.ctrlKey) && e.key === '[') {
                e.preventDefault();
                const idx = sortedSessions.findIndex(s => s.id === sessionId);
                if (idx > 0) handleSelectSession(sortedSessions[idx - 1].id);
            }

            // Cmd+] — switch to next session
            if ((e.metaKey || e.ctrlKey) && e.key === ']') {
                e.preventDefault();
                const idx = sortedSessions.findIndex(s => s.id === sessionId);
                if (idx >= 0 && idx < sortedSessions.length - 1) handleSelectSession(sortedSessions[idx + 1].id);
            }

            // Cmd+Shift+C — copy conversation as Markdown
            if ((e.metaKey || e.ctrlKey) && e.shiftKey && e.key === 'c') {
                e.preventDefault();
                handleCopyConversation();
                return;
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [sessionId, sidebarSessions, sortedSessions, handleNewSession, handleDeleteSession, handleSelectSession, handleCopyConversation]);

    // Global keyboard shortcut: y = approve / n = reject first pending tool call
    useEffect(() => {
        const handler = (e: KeyboardEvent) => {
            // Skip if typing in input/textarea/contenteditable
            const tag = (e.target as HTMLElement)?.tagName;
            if (tag === 'INPUT' || tag === 'TEXTAREA' || (e.target as HTMLElement)?.isContentEditable) return;
            // Skip if any modifier key is pressed
            if (e.metaKey || e.ctrlKey || e.altKey) return;
            if (e.key !== 'y' && e.key !== 'n') return;

            // Find first pending tool call across all messages
            let pendingToolCallId: string | null = null;
            for (const msg of messages) {
                const pending = msg.toolCalls?.find(tc => tc.status === 'pending');
                if (pending) {
                    pendingToolCallId = pending.id;
                    break;
                }
            }
            if (!pendingToolCallId) return;
            e.preventDefault();

            const approved = e.key === 'y';
            handleApproveTool(pendingToolCallId, approved);
            addToast(
                approved ? 'success' : 'info',
                approved ? 'Tool approved' : 'Tool rejected',
            );
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [messages, handleApproveTool, addToast]);

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
    const handleSettingsSaved = useCallback((cfg: ServerConfig) => {
        setServerConfig(cfg);
        setCurrentModel(cfg.defaultModel);
        savePref('model', cfg.defaultModel);
    }, [setCurrentModel]);

    const handleOpenSearch = useCallback(() => {
        setShowSearch(v => {
            if (!v) setSearchQuery('');
            return !v;
        });
    }, []);

    const handleOpenShortcuts = useCallback(() => setShowShortcuts(true), []);

    const handleMenuClick = useCallback(() => setSidebarOpen(v => !v), []);

    const handleModelChange = useCallback((m: string) => {
        setCurrentModel(m);
        savePref('model', m);
    }, [setCurrentModel]);

    const handleToggleFileTree = useCallback(() => {
        setFileTreeOpen(prev => {
            savePref('fileTreeOpen', !prev);
            return !prev;
        });
    }, []);

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

    const currentDraft = useMemo(
        () => (sessionId ? loadDraft(sessionId) : ''),
        [sessionId],
    );

    const sessionStats = useMemo(() => {
        const userMessages = messages.filter(m => m.role === 'user').length;
        const assistantMessages = messages.filter(m => m.role === 'assistant').length;
        const toolCalls = messages.reduce((sum, m) => sum + m.toolCalls.length, 0);
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
        const block = `\`\`\`${language}\n// ${path}\n${content}\n\`\`\`\n`;
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
        handleSend(instruction);
    }, [handleSend]);

    // Stable callbacks for SessionSidebar (prevents memo-busting re-renders)
    const handleCreateSession = useCallback(async (workingDir: string, model: string) => {
        connect();
        const newId = await createSession(workingDir, model);
        return { sessionId: newId };
    }, [connect, createSession]);

    const handleNewSessionFromSidebar = useCallback(({ sessionId: newId }: { sessionId: string }) => {
        setSessionId(newId);
        clearMessages();
        assistantMsgRef.current = null;
        connect();
    }, [setSessionId, clearMessages, connect]);

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
            id: 'toggle-file-tree',
            label: 'Toggle File Tree',
            icon: <FolderTree size={16} />,
            action: () => { handleToggleFileTree(); setShowCommandPalette(false); },
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
    ], [handleNewSession, handleToggleFileTree, handleOpenSettings, handleToggleTheme, handleExport, handleCopyConversation, messages.length, sortedSessions, sessionId, handleSelectSession, showSearch]);

    return (
        <div className="h-screen flex flex-col bg-[var(--bg-primary)]">
            <Header
                currentModel={currentModel}
                tokenUsage={tokenUsage}
                estimatedCost={estimatedCost}
                onToggleTheme={handleToggleTheme}
                onOpenSettings={handleOpenSettings}
                onToggleFileTree={handleToggleFileTree}
                fileTreeOpen={fileTreeOpen}
                searchActive={showSearch}
                onOpenSearch={handleOpenSearch}
                onOpenShortcuts={handleOpenShortcuts}
                onOpenMemory={handleOpenMemory}
                exportAction={
                    <ExportMenu
                        onExport={handleExport}
                        onExportSuccess={handleExportSuccess}
                        onCopy={handleCopyConversation}
                        disabled={messages.length === 0}
                    />
                }
                sessionStats={sessionStats}
                models={serverConfig?.availableModels ?? []}
                onModelChange={handleModelChange}
                isThinking={isThinking}
                isMobile={isMobile}
                onMenuClick={handleMenuClick}
                connectionStatus={connectionStatus}
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
                            />
                        </div>
                    </>
                ) : (
                    <SessionSidebar
                        activeSessionId={sessionId}
                        loadingSessionId={loadingSessionId}
                        onSelectSession={handleSelectSession}
                        onDeleteSession={handleDeleteSession}
                        onCreateSession={handleCreateSession}
                        onNewSession={handleNewSessionFromSidebar}
                        onSessionsChange={setSidebarSessions}
                        sortOrder={sessionSortOrder}
                        onSortChange={setSessionSortOrder}
                    />
                )}

                <FileTreePanel
                    isOpen={fileTreeOpen}
                    onToggle={handleToggleFileTree}
                    onInsertFile={handleInsertFile}
                    onMentionFile={handleMentionFile}
                    width={isNarrow ? 200 : 240}
                />

                <main className="relative flex-1 flex flex-col min-w-0">
                    {/* Chat area */}
                    {messages.length === 0 && connectionStatus !== 'connecting' ? (
                        <WelcomeScreen
                            onSelectPrompt={handleSend}
                            appVersion={__APP_VERSION__}
                        />
                    ) : messages.length === 0 ? (
                        <div className="flex-1 flex items-center justify-center text-[var(--text-muted)]">
                            <div className="text-sm">Connecting…</div>
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
                            className="flex-1 px-4 py-4"
                            data={filteredMessages}
                            followOutput={(showSearch && searchQuery) || (showMessageSearch && messageSearchQuery) ? false : "smooth"}
                            atBottomStateChange={(bottom) => setAtBottom(bottom)}
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
                                    <div className="max-w-3xl mx-auto">
                                        {showDateSep && (
                                            <div className="flex items-center gap-3 px-4 py-2">
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
                                        />
                                    </div>
                                );
                            }}
                            components={{
                                Footer: () =>
                                    isThinking && !streamingMsgId ? (
                                        <div className="max-w-3xl mx-auto">
                                            <ThinkingIndicator
                                                isVisible={true}
                                                phase={agentPhase}
                                                toolName={currentToolName}
                                                toolElapsed={toolElapsed}
                                            />
                                            {lastTool && (
                                                <LastToolDisplay name={lastTool.name} elapsed={lastTool.elapsed} />
                                            )}
                                        </div>
                                    ) : null,
                            }}
                        />
                        </ErrorBoundary>

                        {/* Scroll to bottom button */}
                        {!atBottom && (
                            <div className="absolute bottom-20 right-6 z-10">
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

                    {/* Connection status bar */}
                    {sessionId && !isConnected && (
                        <div className="px-4 py-1 text-xs text-center bg-[var(--color-warning-bg)] text-[var(--color-warning)]">
                            Reconnecting...
                        </div>
                    )}

                    {/* Input */}
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
                        appendText={chatInputAppend}
                        onAppendConsumed={() => setChatInputAppend('')}
                    />
                </main>
            </div>

            {showSettings && serverConfig && (
                <SettingsModal
                    isOpen={showSettings}
                    onClose={handleCloseSettings}
                    config={serverConfig}
                    onSaved={handleSettingsSaved}
                />
            )}

            <SearchPanel
                isOpen={showSearch}
                onClose={() => setShowSearch(false)}
                onInsertResult={(text) => setChatInputAppend(text)}
            />

            <CommandPalette
                isOpen={showCommandPalette}
                onClose={() => setShowCommandPalette(false)}
                commands={commands}
            />

            <ShortcutsModal isOpen={showShortcuts} onClose={() => setShowShortcuts(false)} />

            <ToastContainer toasts={toasts} onDismiss={dismissToast} />

            {showMemoryEditor && serverConfig && (
                <MemoryEditorPanel
                    workingDir={serverConfig.workingDir}
                    onClose={() => setShowMemoryEditor(false)}
                />
            )}

            {showPromptTemplates && (
                <PromptTemplatesPanel
                    onClose={() => setShowPromptTemplates(false)}
                    onInsert={handleInsertTemplate}
                />
            )}
        </div>
    );
}

export default App;
