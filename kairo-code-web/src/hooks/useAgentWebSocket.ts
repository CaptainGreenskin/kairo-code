import { useCallback, useEffect, useRef, useState } from 'react';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import type { AgentEvent } from '@/types/agent';

const WS_ENDPOINT = '/ws';
const SUBSCRIBE_PREFIX = '/topic/session';
const SEND_DESTINATIONS = {
    chat: '/app/chat',
    approve: '/app/approve',
    stop: '/app/stop',
    bindSession: '/app/bind-session',
} as const;

const MAX_RECONNECT_ATTEMPTS = 3;
const INITIAL_RECONNECT_DELAY = 1000;

interface UseAgentWebSocketReturn {
    isConnected: boolean;
    isThinking: boolean;
    connect: (sessionId: string) => void;
    disconnect: () => void;
    sendMessage: (text: string) => void;
    approveTool: (toolCallId: string, approved: boolean) => void;
    stopAgent: () => void;
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

    const createConnection = useCallback(
        (sessionId: string) => {
            cleanup();
            sessionIdRef.current = sessionId;

            const url = `${WS_ENDPOINT}/${sessionId}`;
            const client = new Client({
                webSocketFactory: () => new SockJS(url),
                reconnectDelay: 0,
                heartbeatIncoming: 10000,
                heartbeatOutgoing: 10000,
                onConnect: () => {
                    setIsConnected(true);
                    reconnectAttemptsRef.current = 0;
                    subscriptionRef.current = client.subscribe(
                        `${SUBSCRIBE_PREFIX}/${sessionId}`,
                        (message: IMessage) => {
                            try {
                                const event = JSON.parse(message.body) as AgentEvent;
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
                            createConnection(sessionIdRef.current!);
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
        (text: string) => {
            publish(SEND_DESTINATIONS.chat, { text });
        },
        [publish],
    );

    const approveTool = useCallback(
        (toolCallId: string, approved: boolean) => {
            publish(SEND_DESTINATIONS.approve, { toolCallId, approved });
        },
        [publish],
    );

    const stopAgent = useCallback(() => {
        publish(SEND_DESTINATIONS.stop, {});
    }, [publish]);

    const bindSession = useCallback(
        (sessionId: string) => {
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
        bindSession,
    };
}
