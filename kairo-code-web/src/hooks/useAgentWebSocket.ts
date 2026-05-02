import { useCallback, useEffect, useRef, useState } from 'react';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import type { AgentEvent, ConnectionStatus } from '@/types/agent';
import { useSessionStore } from '@store/sessionStore';

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
                    errorType: (raw.errorType as string) ?? '',
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
        case 'PLAN_STEPS': {
            // content field holds JSON array of step strings
            const stepsContent = raw.content as string;
            let steps: string[];
            try {
                steps = JSON.parse(stepsContent);
            } catch {
                steps = [];
            }
            return {
                type: 'PLAN_STEPS', sessionId, timestamp: ts,
                payload: { steps },
            };
        }
        case 'PLAN_STEP_DONE': {
            // content field holds the step index as string
            const stepIndex = parseInt(raw.content as string, 10);
            return {
                type: 'PLAN_STEP_DONE', sessionId, timestamp: ts,
                payload: { stepIndex: isNaN(stepIndex) ? -1 : stepIndex },
            };
        }
        case 'CONTEXT_COMPACTED': {
            // content field holds JSON: {beforeTokens, maxTokens, ratio}
            const compactContent = raw.content as string;
            let parsed: { beforeTokens: number; maxTokens: number; ratio: number };
            try {
                const obj = JSON.parse(compactContent ?? '{}');
                parsed = {
                    beforeTokens: typeof obj.beforeTokens === 'number' ? obj.beforeTokens : 0,
                    maxTokens: typeof obj.maxTokens === 'number' ? obj.maxTokens : 0,
                    ratio: typeof obj.ratio === 'number' ? obj.ratio : 0,
                };
            } catch {
                parsed = { beforeTokens: 0, maxTokens: 0, ratio: 0 };
            }
            return {
                type: 'CONTEXT_COMPACTED', sessionId, timestamp: ts,
                payload: parsed,
            };
        }
        default:
            return { type: 'AGENT_ERROR', sessionId, timestamp: ts, payload: { message: `Unknown event type: ${type}` } };
    }
}

interface UseAgentWebSocketReturn {
    isConnected: boolean;
    isThinking: boolean;
    connectionStatus: ConnectionStatus;
    stompClient: Client | null;
    connect: () => void;
    disconnect: () => void;
    sendMessage: (sessionId: string, text: string) => void;
    approveTool: (sessionId: string, toolCallId: string, approved: boolean) => void;
    stopAgent: (sessionId: string) => void;
    createSession: (workingDir: string) => Promise<string>;
    bindSession: (sessionId: string) => void;
    switchSession: (sessionId: string) => void;
}

export function useAgentWebSocket(
    onEvent: (event: AgentEvent) => void,
): UseAgentWebSocketReturn {
    const [isConnected, setIsConnected] = useState(false);
    const [isThinking, setIsThinking] = useState(false);
    const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>('disconnected');
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
            setConnectionStatus('connecting');

            const client = new Client({
                webSocketFactory: () => new SockJS(WS_ENDPOINT),
                reconnectDelay: 0,
                // Spring SockJS only sends SockJS-level heartbeats, not STOMP-level ones.
                // Setting heartbeatIncoming > 0 would cause the client to disconnect every 10s.
                heartbeatIncoming: 0,
                heartbeatOutgoing: 10000,
                onConnect: () => {
                    setIsConnected(true);
                    setConnectionStatus('connected');
                    reconnectAttemptsRef.current = 0;

                    // Resolve session ID from cascading sources:
                    // 1. Already-bound ref (e.g., after createSession)
                    // 2. Zustand store (e.g., after handleSelectSession set the new ID)
                    // 3. sessionStorage (e.g., after page reload)
                    let sid = sessionIdRef.current;
                    if (!sid) {
                        sid = useSessionStore.getState().sessionId;
                    }
                    if (!sid) {
                        sid = sessionStorage.getItem(SESSION_STORAGE_KEY);
                    }

                    if (!sid) return;

                    // Persist resolved session ID
                    sessionIdRef.current = sid;
                    sessionStorage.setItem(SESSION_STORAGE_KEY, sid);

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
                                if (event.type === 'AGENT_ERROR') {
                                    const p = event.payload as { errorType?: string };
                                    if (p.errorType === 'SESSION_NOT_FOUND') {
                                        sessionIdRef.current = null;
                                        sessionStorage.removeItem(SESSION_STORAGE_KEY);
                                    }
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

                    // Publish bind-session to request history restore.
                    // Safe to call even for fresh sessions (backend returns empty messages).
                    client.publish({
                        destination: SEND_DESTINATIONS.bindSession,
                        body: JSON.stringify({ sessionId: sid }),
                    });
                },
                onStompError: (frame) => {
                    console.error('[WebSocket] STOMP error:', frame);
                    setIsConnected(false);
                    setConnectionStatus('error');
                },
                onWebSocketClose: () => {
                    setIsConnected(false);
                    setConnectionStatus('disconnected');
                    // Auto-reconnect with exponential backoff (1s → 2s → 4s → … → 30s cap), infinite attempts
                    if (sessionIdRef.current) {
                        const delay = Math.min(1000 * Math.pow(2, reconnectAttemptsRef.current), 30000);
                        reconnectAttemptsRef.current += 1;
                        reconnectTimerRef.current = setTimeout(() => {
                            reconnectTimerRef.current = null;
                            createConnection();
                        }, delay);
                    }
                },
                onWebSocketError: (event) => {
                    console.error('[WebSocket] WS error:', event);
                    setConnectionStatus('error');
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
        setConnectionStatus('disconnected');
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
        (workingDir: string): Promise<string> => {
            return new Promise((resolve, reject) => {
                // Wait up to 5 s for the WebSocket to connect (connect() may still be handshaking)
                const waitAndCreate = (attemptsLeft: number) => {
                    if (clientRef.current?.connected) {
                        doCreate();
                    } else if (attemptsLeft > 0) {
                        setTimeout(() => waitAndCreate(attemptsLeft - 1), 200);
                    } else {
                        reject(new Error('[WebSocket] Not connected'));
                    }
                };

                const doCreate = () => {
                    const timeout = setTimeout(() => {
                        sub?.unsubscribe();
                        reject(new Error('[WebSocket] createSession timeout'));
                    }, 10000);
                    const sub = clientRef.current!.subscribe('/topic/session/created', (message: IMessage) => {
                        try {
                            const resp = JSON.parse(message.body) as { sessionId: string };
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
                        body: JSON.stringify({ workingDir }),
                    });
                };

                waitAndCreate(25); // up to 5 s (25 × 200 ms)
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

    const switchSession = useCallback(
        (sid: string) => {
            if (!clientRef.current?.connected) {
                console.warn('[WebSocket] Not connected, cannot switch session');
                return;
            }
            if (subscriptionRef.current) {
                subscriptionRef.current.unsubscribe();
                subscriptionRef.current = null;
            }
            sessionIdRef.current = sid;
            sessionStorage.setItem(SESSION_STORAGE_KEY, sid);
            subscriptionRef.current = clientRef.current.subscribe(
                `${SUBSCRIBE_PREFIX}/${sid}`,
                (message: IMessage) => {
                    try {
                        const raw = JSON.parse(message.body);
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
                            const payload = event.payload as { isThinking: boolean };
                            setIsThinking(payload.isThinking);
                        }
                        if (event.type === 'AGENT_ERROR') {
                            const p = event.payload as { errorType?: string };
                            if (p.errorType === 'SESSION_NOT_FOUND') {
                                sessionIdRef.current = null;
                                sessionStorage.removeItem(SESSION_STORAGE_KEY);
                            }
                        }
                        onEventRef.current(event);
                    } catch {
                        console.warn('[WebSocket] Failed to parse message:', message.body);
                    }
                },
            );
            clientRef.current.publish({
                destination: SEND_DESTINATIONS.bindSession,
                body: JSON.stringify({ sessionId: sid }),
            });
        },
        [],
    );

    useEffect(() => {
        return cleanup;
    }, [cleanup]);

    return {
        isConnected,
        isThinking,
        connectionStatus,
        stompClient: clientRef.current,
        connect: createConnection,
        disconnect,
        sendMessage,
        approveTool,
        stopAgent,
        createSession,
        bindSession,
        switchSession,
    };
}
