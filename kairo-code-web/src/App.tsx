import { useCallback, useEffect, useRef, useState } from 'react';
import { useSessionStore } from '@store/sessionStore';
import { useAgentWebSocket } from '@hooks/useAgentWebSocket';
import { Header } from '@components/Header';
import { ChatMessage, ThinkingIndicator } from '@components/ChatMessage';
import { ChatInput } from '@components/ChatInput';
import { Sidebar } from '@components/Sidebar';
import type { AgentEvent, ToolCall } from '@/types/agent';
import { createSession as apiCreateSession, getConfig } from '@api/config';

interface SessionItem {
    id: string;
    name: string;
    createdAt: number;
}

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
        appendChunk,
        addToolCall,
        updateToolCall,
        setThinking,
        setTokenUsage,
        setEstimatedCost,
        setCurrentModel,
        clearMessages,
    } = useSessionStore();

    const [sessions, setSessions] = useState<SessionItem[]>([]);
    const assistantMsgRef = useRef<string | null>(null);

    const handleEvent = useCallback(
        (event: AgentEvent) => {
            switch (event.type) {
                case 'TEXT_CHUNK': {
                    const text = (event.payload as { text: string }).text;
                    if (!assistantMsgRef.current) {
                        const msgId = generateId();
                        assistantMsgRef.current = msgId;
                        addMessage({
                            id: msgId,
                            role: 'assistant',
                            content: text,
                            toolCalls: [],
                            timestamp: Date.now(),
                        });
                    } else {
                        appendChunk(assistantMsgRef.current, text);
                    }
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
                    assistantMsgRef.current = null;
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
                    }
                    break;
                }
            }
        },
        [
            addMessage,
            appendChunk,
            addToolCall,
            updateToolCall,
            setThinking,
            setTokenUsage,
            setEstimatedCost,
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
                apiCreateSession('.', currentModel || 'gpt-4')
                    .then(({ sessionId: newId }) => {
                        setSessionId(newId);
                        setSessions((prev) => [
                            ...prev,
                            { id: newId, name: text.slice(0, 40), createdAt: Date.now() },
                        ]);
                        connect(newId);
                        // Wait a tick for WS to connect, then send
                        setTimeout(() => sendMessage(text), 200);
                    })
                    .catch((err) => {
                        console.error('[App] Failed to create session:', err);
                    });
            } else {
                sendMessage(text);
            }
        },
        [sessionId, currentModel, addMessage, setSessionId, connect, sendMessage],
    );

    const handleStop = useCallback(() => {
        stopAgent();
    }, [stopAgent]);

    const handleApproveTool = useCallback(
        (toolCallId: string, approved: boolean) => {
            approveTool(toolCallId, approved);
        },
        [approveTool],
    );

    const handleNewSession = useCallback(() => {
        disconnect();
        setSessionId(null);
        clearMessages();
        assistantMsgRef.current = null;
        setTokenUsage({ input: 0, output: 0 });
        setEstimatedCost(0);
    }, [disconnect, setSessionId, clearMessages, setTokenUsage, setEstimatedCost]);

    const handleSelectSession = useCallback(
        (id: string) => {
            if (id === sessionId) return;
            disconnect();
            setSessionId(id);
            clearMessages();
            assistantMsgRef.current = null;
            connect(id);
        },
        [sessionId, disconnect, setSessionId, clearMessages, connect],
    );

    const handleDeleteSession = useCallback(
        (id: string) => {
            setSessions((prev) => prev.filter((s) => s.id !== id));
            if (id === sessionId) {
                handleNewSession();
            }
        },
        [sessionId, handleNewSession],
    );

    const handleToggleTheme = useCallback(() => {
        // Theme toggling is handled by the Header component via DOM classList
    }, []);

    // Scroll to bottom on new messages
    const messagesEndRef = useRef<HTMLDivElement>(null);
    useEffect(() => {
        messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, [messages]);

    return (
        <div className="h-screen flex flex-col bg-[var(--bg-primary)]">
            <Header
                currentModel={currentModel}
                tokenUsage={tokenUsage}
                estimatedCost={estimatedCost}
                onToggleTheme={handleToggleTheme}
            />

            <div className="flex flex-1 overflow-hidden">
                <Sidebar
                    sessions={sessions}
                    activeSessionId={sessionId}
                    onSelectSession={handleSelectSession}
                    onDeleteSession={handleDeleteSession}
                    onNewSession={handleNewSession}
                />

                <main className="flex-1 flex flex-col min-w-0">
                    {/* Chat area */}
                    <div className="flex-1 overflow-y-auto px-4 py-4">
                        {messages.length === 0 ? (
                            <div className="h-full flex items-center justify-center text-[var(--text-muted)]">
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
                            <div className="max-w-3xl mx-auto">
                                {messages.map((msg) => (
                                    <ChatMessage
                                        key={msg.id}
                                        message={msg}
                                        onApproveTool={handleApproveTool}
                                    />
                                ))}
                                {isThinking && !messages[messages.length - 1]?.content && (
                                    <ThinkingIndicator />
                                )}
                                <div ref={messagesEndRef} />
                            </div>
                        )}
                    </div>

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
