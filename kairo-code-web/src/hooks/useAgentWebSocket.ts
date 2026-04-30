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
} as const;

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
                                const event = raw as AgentEvent;
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
    };
}
