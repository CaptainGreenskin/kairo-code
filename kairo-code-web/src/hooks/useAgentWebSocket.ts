import { useCallback, useEffect, useRef, useState } from 'react';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import type { AgentEvent } from '@/types/agent';

const WS_ENDPOINT = '/ws';
const SUBSCRIBE_PREFIX = '/topic/session';
const SEND_DESTINATIONS = {
    message: '/app/agent/message',
    approve: '/app/agent/approve',
    stop: '/app/agent/stop',
    create: '/app/agent/create',
    bindSession: '/app/agent/bind-session',
} as const;

const SESSION_STORAGE_KEY = 'kairo-code-session-id';

/**
 * Transform backend AgentEvent record (flat fields) to frontend AgentEvent (payload-based).
 *
 * Backend record: {type, sessionId, content, toolName, toolInput, requiresApproval,
 *                  toolCallId, toolResult, tokenUsage, cost, errorMessage, errorType, timestamp}
 * Frontend type:  {type, sessionId, payload, timestamp}
 */
function transformEvent(raw: Record<string, unknown>): AgentEvent {
    const type = raw.type as string;
    const sessionId = raw.sessionId as string;
    const ts = (raw.timestamp as number) ?? Date.now();

    switch (type) {
        case 'TEXT_CHUNK':
            return { type: 'TEXT_CHUNK', sessionId, payload: { text: (raw.content as string) ?? '' }, timestamp: ts };
        case 'TOOL_CALL':
            return {
                type: 'TOOL_CALL', sessionId, timestamp: ts,
                payload: {
                    toolCallId: (raw.toolCallId as string) ?? '',
                    toolName: (raw.toolName as string) ?? '',
                    input: (raw.toolInput as Record<string, unknown>) ?? {},
                    requiresApproval: (raw.requiresApproval as boolean) ?? false,
                },
            };
        case 'TOOL_RESULT':
            return {
                type: 'TOOL_RESULT', sessionId, timestamp: ts,
                payload: {
                    toolCallId: (raw.toolCallId as string) ?? '',
                    result: (raw.toolResult as string) ?? '',
                    isError: false,
                    durationMs: 0,
                },
            };
        case 'AGENT_DONE':
            return {
                type: 'AGENT_DONE', sessionId, timestamp: ts,
                payload: {
                    inputTokens: (raw.tokenUsage as number) ?? 0,
                    outputTokens: 0,
                },
            };
        case 'AGENT_ERROR':
            return {
                type: 'AGENT_ERROR', sessionId, timestamp: ts,
                payload: {
                    message: (raw.errorMessage as string) ?? 'Unknown error',
                },
            };
        case 'AGENT_THINKING':
            return {
                type: 'AGENT_THINKING', sessionId, timestamp: ts,
                payload: { isThinking: true },
            };
        case 'SESSION_RESTORED': {
            // content field holds JSON: {messages: [...], running: boolean}
            const content = raw.content as string;
            let parsed: { messages: unknown[]; running: boolean };
            try {
                parsed = JSON.parse(content);
            } catch {
                parsed = { messages: [], running: false };
            }
            return {
                type: 'SESSION_RESTORED', sessionId, timestamp: ts,
                payload: parsed as import('@/types/agent').SessionRestoredPayload,
            };
        }
        default:
            return { type: 'AGENT_ERROR', sessionId, timestamp: ts, payload: { message: `Unknown event type: ${type}` } };
    }
}

const MAX_RECONNECT_ATTEMPTS = 3;
const INITIAL_RECONNECT_DELAY = 1000;

interface UseAgentWebSocketReturn {
    isConnected: boolean;
    isThinking: boolean;
    connect: () => void;
    disconnect: () => void;
    sendMessage: (sessionId: string, text: string) => void;
    approveTool: (sessionId: string, toolCallId: string, approved: boolean) => void;
    stopAgent: (sessionId: string) => void;
    createSession: (workingDir: string, model: string) => Promise<string>;
    bindSession: (sessionId: string) => void;
}

export function useAgentWebSocket(
    onEvent: (event: AgentEvent) => void,
): UseAgentWebSocketReturn {
    const [isConnected, setIsConnected] = useState(false);
    const [isThinking, setIsThinking] = useState(false);
    const clientRef = useRef<Client | null>(null);
    const subscriptionRef = useRef<StompSubscription | null>(null);
    const reconnectAttemptsRef = useRef(0);
    const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const onEventRef = useRef(onEvent);
    const sessionIdRef = useRef<string | null>(null);

    useEffect(() => {
        onEventRef.current = onEvent;
    }, [onEvent]);

    const cleanup = useCallback(() => {
        if (reconnectTimerRef.current) {
            clearTimeout(reconnectTimerRef.current);
            reconnectTimerRef.current = null;
        }
        if (subscriptionRef.current) {
            subscriptionRef.current.unsubscribe();
            subscriptionRef.current = null;
        }
        if (clientRef.current) {
            clientRef.current.deactivate();
            clientRef.current = null;
        }
        reconnectAttemptsRef.current = 0;
    }, []);

    const createConnection = useCallback(() => {
            cleanup();

            const client = new Client({
                webSocketFactory: () => new SockJS(WS_ENDPOINT),
                reconnectDelay: 0,
                heartbeatIncoming: 10000,
                heartbeatOutgoing: 10000,
                onConnect: () => {
                    setIsConnected(true);
                    reconnectAttemptsRef.current = 0;
                    const sid = sessionIdRef.current;
                    if (!sid) return;
                    subscriptionRef.current = client.subscribe(
                        `${SUBSCRIBE_PREFIX}/${sid}`,
                        (message: IMessage) => {
                            try {
                                const raw = JSON.parse(message.body);
                                // CreateSessionResponse also arrives on this topic
                                if ('workingDir' in raw && 'model' in raw && !('type' in raw)) {
                                    const resp = raw as { sessionId: string };
                                    sessionIdRef.current = resp.sessionId;
                                    onEventRef.current({
                                        type: 'AGENT_THINKING',
                                        sessionId: resp.sessionId,
                                        payload: { isThinking: false },
                                        timestamp: Date.now(),
                                    } as AgentEvent);
                                    return;
                                }
                                const event = transformEvent(raw);
                                if (event.type === 'AGENT_THINKING') {
                                    const payload = event.payload as {
                                        isThinking: boolean;
                                    };
                                    setIsThinking(payload.isThinking);
                                }
                                onEventRef.current(event);
                            } catch {
                                console.warn(
                                    '[WebSocket] Failed to parse message:',
                                    message.body,
                                );
                            }
                        },
                    );
                },
                onStompError: (frame) => {
                    console.error('[WebSocket] STOMP error:', frame);
                    setIsConnected(false);
                },
                onWebSocketClose: () => {
                    setIsConnected(false);
                    // Auto-reconnect with exponential backoff
                    if (
                        reconnectAttemptsRef.current < MAX_RECONNECT_ATTEMPTS &&
                        sessionIdRef.current
                    ) {
                        const delay =
                            INITIAL_RECONNECT_DELAY *
                            Math.pow(2, reconnectAttemptsRef.current);
                        reconnectAttemptsRef.current += 1;
                        reconnectTimerRef.current = setTimeout(() => {
                            createConnection();
                        }, delay);
                    }
                },
                onWebSocketError: (event) => {
                    console.error('[WebSocket] WS error:', event);
                },
            });

            clientRef.current = client;
            client.activate();
        },
        [cleanup],
    );

    const disconnect = useCallback(() => {
        sessionIdRef.current = null;
        sessionStorage.removeItem(SESSION_STORAGE_KEY);
        cleanup();
        setIsConnected(false);
        setIsThinking(false);
    }, [cleanup]);

    const publish = useCallback(
        (destination: string, body: Record<string, unknown>) => {
            if (!clientRef.current?.connected) {
                console.warn('[WebSocket] Not connected, cannot publish');
                return;
            }
            clientRef.current.publish({
                destination,
                body: JSON.stringify(body),
            });
        },
        [],
    );

    const sendMessage = useCallback(
        (sessionId: string, text: string) => {
            publish(SEND_DESTINATIONS.message, { sessionId, message: text });
        },
        [publish],
    );

    const approveTool = useCallback(
        (sessionId: string, toolCallId: string, approved: boolean) => {
            publish(SEND_DESTINATIONS.approve, { sessionId, toolCallId, approved });
        },
        [publish],
    );

    const stopAgent = useCallback(
        (sessionId: string) => {
            publish(SEND_DESTINATIONS.stop, { sessionId });
        },
        [publish],
    );

    const createSession = useCallback(
        (workingDir: string, model: string): Promise<string> => {
            return new Promise((resolve, reject) => {
                if (!clientRef.current?.connected) {
                    reject(new Error('[WebSocket] Not connected'));
                    return;
                }
                const timeout = setTimeout(() => {
                    sub?.unsubscribe();
                    reject(new Error('[WebSocket] createSession timeout'));
                }, 10000);
                const sub = clientRef.current!.subscribe('/topic/session/created', (message: IMessage) => {
                    try {
                        const resp = JSON.parse(message.body) as {
                            sessionId: string;
                        };
                        sub.unsubscribe();
                        clearTimeout(timeout);
                        sessionIdRef.current = resp.sessionId;
                        sessionStorage.setItem(SESSION_STORAGE_KEY, resp.sessionId);
                        resolve(resp.sessionId);
                    } catch (e) {
                        sub.unsubscribe();
                        clearTimeout(timeout);
                        reject(e);
                    }
                });
                clientRef.current!.publish({
                    destination: SEND_DESTINATIONS.create,
                    body: JSON.stringify({ workingDir, model }),
                });
            });
        },
        [],
    );

    const bindSession = useCallback(
        (sessionId: string) => {
            sessionIdRef.current = sessionId;
            sessionStorage.setItem(SESSION_STORAGE_KEY, sessionId);
            publish(SEND_DESTINATIONS.bindSession, { sessionId });
        },
        [publish],
    );

    useEffect(() => {
        return cleanup;
    }, [cleanup]);

    return {
        isConnected,
        isThinking,
        connect: createConnection,
        disconnect,
        sendMessage,
        approveTool,
        stopAgent,
        createSession,
        bindSession,
    };
}
