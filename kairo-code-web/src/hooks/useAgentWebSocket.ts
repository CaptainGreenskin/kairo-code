import { useCallback, useEffect, useRef, useState } from 'react';
import type { AgentEvent, ConnectionStatus } from '@/types/agent';
import { useSessionStore } from '@store/sessionStore';

/**
 * Agent WebSocket hook — talks to /ws/agent over native WebSocket + JSON.
 *
 * Wire protocol (mirrors AgentWebSocketHandler.java):
 *
 *   client → server  {"action":"bind"|"create"|"message"|"approve"|"stop", ...}
 *   server → client  AgentEvent JSON OR {"type":"SESSION_CREATED",...} OR {"type":"ERR",...}
 */

const WS_PATH = '/ws/agent';
const SESSION_STORAGE_KEY = 'kairo-code-session-id';
const RECONNECT_MAX_DELAY = 30_000;
const STALLED_RUNNING_TIMEOUT_MS = 10_000;

function buildWsUrl(): string {
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${proto}//${window.location.host}${WS_PATH}`;
}

/**
 * Transform backend AgentEvent record (flat fields) to frontend AgentEvent (payload-based).
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
                payload: { inputTokens: (raw.tokenUsage as number) ?? 0, outputTokens: 0 },
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
                payload: { isThinking: true, text: (raw.content as string) ?? '' },
            };
        case 'SESSION_RESTORED': {
            let parsed: { messages: unknown[]; running: boolean };
            try {
                parsed = JSON.parse(raw.content as string);
            } catch {
                parsed = { messages: [], running: false };
            }
            return {
                type: 'SESSION_RESTORED', sessionId, timestamp: ts,
                payload: parsed as import('@/types/agent').SessionRestoredPayload,
            };
        }
        case 'TODOS_UPDATED': {
            let todos: import('@/types/agent').Todo[];
            try {
                const parsed = JSON.parse((raw.content as string) ?? '[]');
                todos = Array.isArray(parsed) ? parsed : [];
            } catch {
                todos = [];
            }
            return { type: 'TODOS_UPDATED', sessionId, timestamp: ts, payload: { todos } };
        }
        case 'CONTEXT_COMPACTED': {
            let parsed: { beforeTokens: number; maxTokens: number; ratio: number };
            try {
                const obj = JSON.parse((raw.content as string) ?? '{}');
                parsed = {
                    beforeTokens: typeof obj.beforeTokens === 'number' ? obj.beforeTokens : 0,
                    maxTokens: typeof obj.maxTokens === 'number' ? obj.maxTokens : 0,
                    ratio: typeof obj.ratio === 'number' ? obj.ratio : 0,
                };
            } catch {
                parsed = { beforeTokens: 0, maxTokens: 0, ratio: 0 };
            }
            return { type: 'CONTEXT_COMPACTED', sessionId, timestamp: ts, payload: parsed };
        }
        default:
            return {
                type: 'AGENT_ERROR', sessionId, timestamp: ts,
                payload: { message: `Unknown event type: ${type}` },
            };
    }
}

interface UseAgentWebSocketReturn {
    isConnected: boolean;
    isThinking: boolean;
    connectionStatus: ConnectionStatus;
    connect: () => void;
    disconnect: () => void;
    sendMessage: (sessionId: string, text: string, imageData?: string, imageMediaType?: string) => void;
    approveTool: (
        sessionId: string,
        toolCallId: string,
        approved: boolean,
        reason?: string,
        editedArgs?: Record<string, unknown>,
    ) => void;
    stopAgent: (sessionId: string) => void;
    createSession: (workspaceId: string) => Promise<string>;
    bindSession: (sessionId: string) => void;
    switchSession: (sessionId: string) => void;
}

export function useAgentWebSocket(
    onEvent: (event: AgentEvent) => void,
): UseAgentWebSocketReturn {
    const [isConnected, setIsConnected] = useState(false);
    const [isThinking, setIsThinking] = useState(false);
    const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>('disconnected');

    const wsRef = useRef<WebSocket | null>(null);
    const reconnectAttemptsRef = useRef(0);
    const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const stalledTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const onEventRef = useRef(onEvent);
    const sessionIdRef = useRef<string | null>(null);
    const createPendingRef = useRef<{
        resolve: (sid: string) => void;
        reject: (err: Error) => void;
        timer: ReturnType<typeof setTimeout>;
    } | null>(null);

    useEffect(() => { onEventRef.current = onEvent; }, [onEvent]);

    const clearStalledTimer = useCallback(() => {
        if (stalledTimerRef.current) {
            clearTimeout(stalledTimerRef.current);
            stalledTimerRef.current = null;
        }
    }, []);

    const startStalledProbe = useCallback((sid: string) => {
        clearStalledTimer();
        stalledTimerRef.current = setTimeout(() => {
            stalledTimerRef.current = null;
            console.warn('[ws] SESSION_RESTORED running=true with no activity for 10s — cancelling');
            fetch(`/api/sessions/${sid}/cancel`, { method: 'POST' }).catch(() => {});
        }, STALLED_RUNNING_TIMEOUT_MS);
    }, [clearStalledTimer]);

    const send = useCallback((payload: Record<string, unknown>) => {
        const ws = wsRef.current;
        if (!ws || ws.readyState !== WebSocket.OPEN) {
            console.warn('[ws] not open, cannot send', payload.action);
            return false;
        }
        ws.send(JSON.stringify(payload));
        return true;
    }, []);

    const handleIncoming = useCallback((data: string) => {
        let raw: Record<string, unknown>;
        try {
            raw = JSON.parse(data);
        } catch {
            console.warn('[ws] bad JSON:', data);
            return;
        }

        const type = raw.type as string;

        // Server → client envelopes that aren't AgentEvents
        if (type === 'SESSION_CREATED') {
            const sid = raw.sessionId as string;
            sessionIdRef.current = sid;
            sessionStorage.setItem(SESSION_STORAGE_KEY, sid);
            const pending = createPendingRef.current;
            if (pending) {
                clearTimeout(pending.timer);
                createPendingRef.current = null;
                pending.resolve(sid);
            }
            return;
        }
        if (type === 'ERR') {
            console.warn('[ws] server error:', raw.message);
            const pending = createPendingRef.current;
            if (pending && raw.action === 'create') {
                clearTimeout(pending.timer);
                createPendingRef.current = null;
                pending.reject(new Error(String(raw.message ?? 'create failed')));
            }
            return;
        }

        // Otherwise an AgentEvent
        const event = transformEvent(raw);
        if (event.type === 'AGENT_THINKING') {
            const p = event.payload as { isThinking: boolean };
            setIsThinking(p.isThinking);
            clearStalledTimer();
        }
        if (event.type === 'TEXT_CHUNK' || event.type === 'TOOL_CALL') {
            clearStalledTimer();
        }
        if (event.type === 'SESSION_RESTORED') {
            const p = event.payload as { running: boolean };
            if (p.running) startStalledProbe(event.sessionId);
        }
        if (event.type === 'AGENT_ERROR') {
            const p = event.payload as { errorType?: string };
            if (p.errorType === 'SESSION_NOT_FOUND') {
                sessionIdRef.current = null;
                sessionStorage.removeItem(SESSION_STORAGE_KEY);
            }
        }
        onEventRef.current(event);
    }, [clearStalledTimer, startStalledProbe]);

    const cleanupSocket = useCallback(() => {
        const ws = wsRef.current;
        if (!ws) return;
        wsRef.current = null;
        // Detach handlers so close events don't trigger reconnect
        ws.onopen = null;
        ws.onmessage = null;
        ws.onerror = null;
        ws.onclose = null;
        if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
            try { ws.close(); } catch {}
        }
    }, []);

    const connect = useCallback(() => {
        if (wsRef.current && wsRef.current.readyState <= WebSocket.OPEN) {
            return; // already connecting / connected
        }
        if (reconnectTimerRef.current) {
            clearTimeout(reconnectTimerRef.current);
            reconnectTimerRef.current = null;
        }

        setConnectionStatus('connecting');
        const ws = new WebSocket(buildWsUrl());
        wsRef.current = ws;

        ws.onopen = () => {
            setIsConnected(true);
            setConnectionStatus('connected');
            reconnectAttemptsRef.current = 0;

            // Resolve session ID from cascading sources
            let sid = sessionIdRef.current
                || useSessionStore.getState().sessionId
                || sessionStorage.getItem(SESSION_STORAGE_KEY);
            if (sid) {
                sessionIdRef.current = sid;
                sessionStorage.setItem(SESSION_STORAGE_KEY, sid);
                send({ action: 'bind', sessionId: sid });
            }
        };

        ws.onmessage = (e) => {
            handleIncoming(typeof e.data === 'string' ? e.data : '');
        };

        ws.onerror = (e) => {
            console.warn('[ws] error', e);
            setConnectionStatus('error');
        };

        ws.onclose = () => {
            setIsConnected(false);
            setConnectionStatus('disconnected');
            wsRef.current = null;
            // Always reconnect with exponential backoff. Earlier this only fired when
            // sessionIdRef.current was set, which left the UI stuck at "Disconnected"
            // after a server restart wiped the session — the frontend would clear sid
            // (via SESSION_NOT_FOUND) and then refuse to retry forever. Reconnecting
            // unconditionally lets the user create a fresh session over the new socket.
            // disconnect() still wins via cleanupSocket() detaching this handler.
            if (!reconnectTimerRef.current) {
                const delay = Math.min(1000 * Math.pow(2, reconnectAttemptsRef.current), RECONNECT_MAX_DELAY);
                reconnectAttemptsRef.current += 1;
                reconnectTimerRef.current = setTimeout(() => {
                    reconnectTimerRef.current = null;
                    connect();
                }, delay);
            }
        };
    }, [handleIncoming, send]);

    const disconnect = useCallback(() => {
        sessionIdRef.current = null;
        sessionStorage.removeItem(SESSION_STORAGE_KEY);
        if (reconnectTimerRef.current) {
            clearTimeout(reconnectTimerRef.current);
            reconnectTimerRef.current = null;
        }
        clearStalledTimer();
        cleanupSocket();
        reconnectAttemptsRef.current = 0;
        setIsConnected(false);
        setIsThinking(false);
        setConnectionStatus('disconnected');
    }, [cleanupSocket, clearStalledTimer]);

    const sendMessage = useCallback(
        (sessionId: string, text: string, imageData?: string, imageMediaType?: string) => {
            const body: Record<string, unknown> = { action: 'message', sessionId, message: text };
            if (imageData) {
                body.imageData = imageData;
                body.imageMediaType = imageMediaType;
            }
            send(body);
        },
        [send],
    );

    const approveTool = useCallback(
        (
            sessionId: string,
            toolCallId: string,
            approved: boolean,
            reason?: string,
            editedArgs?: Record<string, unknown>,
        ) => {
            const body: Record<string, unknown> = { action: 'approve', sessionId, toolCallId, approved };
            if (reason && reason.trim()) body.reason = reason.trim();
            if (editedArgs && Object.keys(editedArgs).length > 0) body.editedArgs = editedArgs;
            send(body);
        },
        [send],
    );

    const stopAgent = useCallback(
        (sessionId: string) => {
            send({ action: 'stop', sessionId });
        },
        [send],
    );

    const createSession = useCallback(
        (workspaceId: string): Promise<string> => {
            return new Promise<string>((resolve, reject) => {
                const tryCreate = (attemptsLeft: number) => {
                    const ws = wsRef.current;
                    if (ws && ws.readyState === WebSocket.OPEN) {
                        if (createPendingRef.current) {
                            createPendingRef.current.reject(new Error('superseded'));
                            clearTimeout(createPendingRef.current.timer);
                        }
                        const timer = setTimeout(() => {
                            if (createPendingRef.current) {
                                createPendingRef.current = null;
                                reject(new Error('[ws] createSession timeout'));
                            }
                        }, 10_000);
                        createPendingRef.current = { resolve, reject, timer };
                        send({ action: 'create', workspaceId });
                    } else if (attemptsLeft > 0) {
                        setTimeout(() => tryCreate(attemptsLeft - 1), 200);
                    } else {
                        reject(new Error('[ws] not connected'));
                    }
                };
                tryCreate(25); // up to 5 s
            });
        },
        [send],
    );

    const bindSession = useCallback(
        (sessionId: string) => {
            sessionIdRef.current = sessionId;
            sessionStorage.setItem(SESSION_STORAGE_KEY, sessionId);
            send({ action: 'bind', sessionId });
        },
        [send],
    );

    const switchSession = useCallback(
        (sid: string) => {
            sessionIdRef.current = sid;
            sessionStorage.setItem(SESSION_STORAGE_KEY, sid);
            send({ action: 'bind', sessionId: sid });
        },
        [send],
    );

    useEffect(() => {
        return () => {
            if (reconnectTimerRef.current) clearTimeout(reconnectTimerRef.current);
            clearStalledTimer();
            cleanupSocket();
        };
    }, [cleanupSocket, clearStalledTimer]);

    return {
        isConnected,
        isThinking,
        connectionStatus,
        connect,
        disconnect,
        sendMessage,
        approveTool,
        stopAgent,
        createSession,
        bindSession,
        switchSession,
    };
}
