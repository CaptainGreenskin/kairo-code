import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ArrowDown, Plus, Search, FolderTree, Settings, Moon, HelpCircle } from 'lucide-react';
import { useSessionStore } from '@store/sessionStore';
import { streamingStore } from '@store/streamingStore';
import { useAgentWebSocket } from '@hooks/useAgentWebSocket';
import { Header } from '@components/Header';
import { ChatMessage, ThinkingIndicator } from '@components/ChatMessage';
import { ChatInput } from '@components/ChatInput';
import { SessionSidebar } from '@components/SessionSidebar';
import { SettingsModal } from '@components/SettingsModal';
import { FileTreePanel } from '@components/FileTreePanel';
import { SearchPanel } from '@components/SearchPanel';
import { CommandPalette } from '@components/CommandPalette';
import { ShortcutsModal } from '@components/ShortcutsModal';
import type { Command } from '@components/CommandPalette';
import type { AgentEvent, ToolCall, Message, ServerConfig } from '@/types/agent';
import { getConfig } from '@api/config';
import { Virtuoso } from 'react-virtuoso';

function generateId(): string {
    return crypto.randomUUID();
}

function App() {
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
    const [showSettings, setShowSettings] = useState(false);
    const [serverConfig, setServerConfig] = useState<ServerConfig | null>(null);
    const [fileTreeOpen, setFileTreeOpen] = useState(false);
    const [showSearch, setShowSearch] = useState(false);
    const [chatInputAppend, setChatInputAppend] = useState<string>('');
    const [loadingSessionId, setLoadingSessionId] = useState<string | null>(null);
    const [showCommandPalette, setShowCommandPalette] = useState(false);
    const [showShortcuts, setShowShortcuts] = useState(false);
    const virtuosoRef = useRef<import('react-virtuoso').VirtuosoHandle>(null);
    const [atBottom, setAtBottom] = useState(true);
    const [unreadCount, setUnreadCount] = useState(0);
    const prevMsgCount = useRef(messages.length);

    const handleEvent = useCallback(
        (event: AgentEvent) => {
            switch (event.type) {
                case 'TEXT_CHUNK': {
                    const text = (event.payload as { text: string }).text;
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
                    setLoadingSessionId(null);
                    addMessage({
                        id: generateId(),
                        role: 'assistant',
                        content: `Error: ${payload.message}`,
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
        ],
    );

    const {
        isConnected,
        isThinking: wsThinking,
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

    // Session restore: if sessionStorage has a sessionId, reconnect and auto-bind
    useEffect(() => {
        const savedId = sessionStorage.getItem('kairo-code-session-id');
        if (savedId) {
            connect();
        }
        // onConnect auto-detects the session and sends bind-session
    }, []); // eslint-disable-line react-hooks/exhaustive-deps

    // Global keyboard shortcut: Cmd+Shift+F opens search
    useEffect(() => {
        const handler = (e: KeyboardEvent) => {
            if ((e.metaKey || e.ctrlKey) && e.shiftKey && e.key === 'f') {
                e.preventDefault();
                setShowSearch(true);
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
                        sendMessage(newId, text);
                    })
                    .catch((err) => {
                        console.error('[App] Failed to create session:', err);
                    });
            } else {
                sendMessage(sessionId, text);
            }
        },
        [sessionId, currentModel, addMessage, setSessionId, connect, createSession, sendMessage],
    );

    const handleStop = useCallback(() => {
        if (sessionId) stopAgent(sessionId);
    }, [stopAgent, sessionId]);

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

    const handleScrollToBottom = useCallback(() => {
        virtuosoRef.current?.scrollToIndex({ index: messages.length - 1, behavior: 'smooth' });
        setUnreadCount(0);
    }, [messages.length]);

    const handleNewSession = useCallback(() => {
        disconnect();
        setSessionId(null);
        clearMessages();
        assistantMsgRef.current = null;
        setStreamingMsgId(null);
        setTokenUsage({ input: 0, output: 0 });
        setEstimatedCost(0);
        sessionStorage.removeItem('kairo-code-session-id');
    }, [disconnect, setSessionId, clearMessages, setTokenUsage, setEstimatedCost]);

    const handleSelectSession = useCallback(
        (id: string) => {
            if (id === sessionId) return;
            setLoadingSessionId(id);
            disconnect();
            setSessionId(id);
            clearMessages();
            assistantMsgRef.current = null;
            setStreamingMsgId(null);
            connect();
        },
        [sessionId, disconnect, setSessionId, clearMessages, connect],
    );

    const handleDeleteSession = useCallback(
        (id: string) => {
            if (id === sessionId) {
                handleNewSession();
            }
        },
        [sessionId, handleNewSession],
    );

    const handleToggleTheme = useCallback(() => {
        // Theme toggling is handled by the Header component via DOM classList
    }, []);

    const handleOpenSettings = useCallback(() => setShowSettings(true), []);
    const handleCloseSettings = useCallback(() => setShowSettings(false), []);
    const handleSettingsSaved = useCallback((cfg: ServerConfig) => {
        setServerConfig(cfg);
        setCurrentModel(cfg.defaultModel);
    }, [setCurrentModel]);

    const handleToggleFileTree = useCallback(() => {
        setFileTreeOpen(prev => !prev);
    }, []);

    const handleInsertFile = useCallback((path: string, content: string, language: string) => {
        const block = `\`\`\`${language}\n// ${path}\n${content}\n\`\`\`\n`;
        setChatInputAppend(block);
    }, []);

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
            label: 'Search Workspace',
            shortcut: '⌘⇧F',
            icon: <Search size={16} />,
            action: () => { setShowSearch(true); setShowCommandPalette(false); },
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
    ], [handleNewSession, handleToggleFileTree, handleOpenSettings, handleToggleTheme]);

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
                onOpenSearch={() => setShowSearch(true)}
                onOpenShortcuts={() => setShowShortcuts(true)}
            />

            <div className="flex flex-1 overflow-hidden">
                <SessionSidebar
                    activeSessionId={sessionId}
                    loadingSessionId={loadingSessionId}
                    onSelectSession={handleSelectSession}
                    onDeleteSession={handleDeleteSession}
                    onCreateSession={async (workingDir, model) => {
                        connect();
                        const newId = await createSession(workingDir, model);
                        return { sessionId: newId };
                    }}
                    onNewSession={({ sessionId: newId }) => {
                        setSessionId(newId);
                        clearMessages();
                        assistantMsgRef.current = null;
                        connect();
                    }}
                />

                <FileTreePanel
                    isOpen={fileTreeOpen}
                    onToggle={handleToggleFileTree}
                    onInsertFile={handleInsertFile}
                />

                <main className="relative flex-1 flex flex-col min-w-0">
                    {/* Chat area */}
                    {messages.length === 0 ? (
                        <div className="flex-1 flex items-center justify-center text-[var(--text-muted)]">
                            <div className="text-center">
                                <div className="text-4xl mb-3">&#128172;</div>
                                <div className="text-lg font-medium text-[var(--text-primary)]">
                                    Start a conversation
                                </div>
                                <div className="text-sm mt-2">
                                    Type a message to begin
                                </div>
                            </div>
                        </div>
                    ) : (
                        <>
                        <Virtuoso
                            ref={virtuosoRef}
                            className="flex-1 px-4 py-4"
                            data={messages}
                            followOutput="smooth"
                            atBottomStateChange={(bottom) => setAtBottom(bottom)}
                            itemContent={(_index, msg) => (
                                <div className="max-w-3xl mx-auto">
                                    <ChatMessage
                                        message={msg as Message}
                                        onApproveTool={handleApproveTool}
                                        isStreaming={(msg as Message).id === streamingMsgId}
                                        sessionId={sessionId ?? undefined}
                                        onRegenerate={handleRegenerate}
                                        onEditResend={handleEditResend}
                                    />
                                </div>
                            )}
                            components={{
                                Footer: () =>
                                    isThinking ? (
                                        <div className="max-w-3xl mx-auto">
                                            <ThinkingIndicator />
                                        </div>
                                    ) : null,
                            }}
                        />

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
                    <ChatInput
                        onSend={handleSend}
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
        </div>
    );
}

export default App;
