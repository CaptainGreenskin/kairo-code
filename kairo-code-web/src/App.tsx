import { useCallback, useEffect, useRef, useState } from 'react';
import { useSessionStore } from '@store/sessionStore';
import { streamingStore } from '@store/streamingStore';
import { useAgentWebSocket } from '@hooks/useAgentWebSocket';
import { Header } from '@components/Header';
import { ChatMessage, ThinkingIndicator } from '@components/ChatMessage';
import { ChatInput } from '@components/ChatInput';
import { SessionSidebar } from '@components/SessionSidebar';
import type { AgentEvent, ToolCall, Message } from '@/types/agent';
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
    const virtuosoRef = useRef<import('react-virtuoso').VirtuosoHandle>(null);

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
        bindSession,
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
            })
            .catch(() => {
                // Backend not running yet
            });
    }, [setCurrentModel]);

    // Session restore: if sessionStorage has a sessionId, reconnect and bind
    useEffect(() => {
        const savedId = sessionStorage.getItem('kairo-code-session-id');
        if (!savedId) return;

        connect();
        // Wait for connection to establish before binding
        const timer = setTimeout(() => {
            bindSession(savedId);
        }, 500);
        return () => clearTimeout(timer);
    }, []); // eslint-disable-line react-hooks/exhaustive-deps

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

    return (
        <div className="h-screen flex flex-col bg-[var(--bg-primary)]">
            <Header
                currentModel={currentModel}
                tokenUsage={tokenUsage}
                estimatedCost={estimatedCost}
                onToggleTheme={handleToggleTheme}
            />

            <div className="flex flex-1 overflow-hidden">
                <SessionSidebar
                    activeSessionId={sessionId}
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

                <main className="flex-1 flex flex-col min-w-0">
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
                        <Virtuoso
                            ref={virtuosoRef}
                            className="flex-1 px-4 py-4"
                            data={messages}
                            followOutput="smooth"
                            itemContent={(_index, msg) => (
                                <div className="max-w-3xl mx-auto">
                                    <ChatMessage
                                        message={msg as Message}
                                        onApproveTool={handleApproveTool}
                                        isStreaming={(msg as Message).id === streamingMsgId}
                                        sessionId={sessionId ?? undefined}
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
                    />
                </main>
            </div>
        </div>
    );
}

export default App;
