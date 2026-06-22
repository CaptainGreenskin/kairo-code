import { useCallback, useEffect, useRef, useState } from 'react';
import type { AgentEvent, ConnectionStatus, PlanReadyPayload } from '@/types/agent';
import { useSessionStore } from '@store/sessionStore';
import { useSessionModeStore } from '@store/sessionModeStore';
import { useBuildPhaseStore } from '@store/buildPhaseStore';
import { useExpertTeamStore } from '@store/expertTeamStore';
import { appendTokenToWsUrl } from '@/api/auth';

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
const STALLED_RUNNING_TIMEOUT_MS = 30_000;

function buildWsUrl(): string {
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return appendTokenToWsUrl(`${proto}//${window.location.host}${WS_PATH}`);
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
        case 'TOOL_RESULT': {
            const meta = (raw.resultMetadata as Record<string, unknown>) ?? {};
            return {
                type: 'TOOL_RESULT', sessionId, timestamp: ts,
                payload: {
                    toolCallId: (raw.toolCallId as string) ?? '',
                    result: (raw.toolResult as string) ?? '',
                    isError: (meta.isError as boolean) ?? false,
                    durationMs: (meta.durationMs as number) ?? 0,
                    resultMetadata: meta,
                },
            };
        }
        case 'AGENT_DONE': {
            const totalTokens = (raw.tokenUsage as number) ?? 0;
            const inputTokens = (raw.inputTokens as number) ?? totalTokens;
            const outputTokens = (raw.outputTokens as number) ?? Math.max(0, totalTokens - inputTokens);
            return {
                type: 'AGENT_DONE', sessionId, timestamp: ts,
                payload: {
                    inputTokens,
                    outputTokens,
                    cost: (raw.cost as number) ?? undefined,
                },
            };
        }
        case 'AGENT_ERROR':
            return {
                type: 'AGENT_ERROR', sessionId, timestamp: ts,
                payload: {
                    message: (raw.errorMessage as string) ?? 'Unknown error',
                    errorType: (raw.errorType as string) ?? '',
                },
            };
        case 'MESSAGE_QUEUED': {
            const meta = (raw.resultMetadata as Record<string, unknown>) ?? {};
            return {
                type: 'MESSAGE_QUEUED', sessionId, timestamp: ts,
                payload: { queuePosition: (meta.queuePosition as number) ?? 1 },
            };
        }
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
        case 'PLAN_READY': {
            // Experts preset stamps teamId + DAG steps on resultMetadata so the
            // Canvas can auto-attach and populate the DAG immediately.
            const meta = (raw.resultMetadata as Record<string, unknown>) ?? {};
            return {
                type: 'PLAN_READY', sessionId, timestamp: ts,
                payload: {
                    planSummary: (raw.content as string) ?? '',
                    teamId: (meta.teamId as string) ?? undefined,
                    goal: (meta.goal as string) ?? undefined,
                    steps: meta.steps as PlanReadyPayload['steps'],
                    mode: (meta.mode as string) ?? undefined,
                    planId: (meta.planId as string) ?? undefined,
                    totalSteps: (meta.totalSteps as number) ?? undefined,
                },
            };
        }
        case 'REVERTED':
            return {
                type: 'REVERTED', sessionId, timestamp: ts,
                payload: { message: (raw.content as string) ?? '' },
            };
        case 'MODE_DEMOTED':
            return {
                type: 'MODE_DEMOTED', sessionId, timestamp: ts,
                payload: { reason: (raw.content as string) ?? '' },
            };
        case 'MODE_ESCALATED':
            return {
                type: 'MODE_ESCALATED', sessionId, timestamp: ts,
                payload: { reason: (raw.content as string) ?? '' },
            };
        case 'SESSION_RESUMED':
            return {
                type: 'SESSION_RESUMED', sessionId, timestamp: ts,
                payload: { reason: (raw.content as string) ?? '' },
            };
        case 'CLEAR_EXECUTION_MESSAGES':
            return {
                type: 'CLEAR_EXECUTION_MESSAGES', sessionId, timestamp: ts,
                payload: {},
            };
        case 'PEER_MESSAGE': {
            // M-Team / #60: peer-to-peer message relayed via the in-process MessageBus.
            // Backend stamps fromSessionId + messageId on resultMetadata (Map<String,Object>).
            const meta = (raw.resultMetadata as Record<string, unknown>) ?? {};
            return {
                type: 'PEER_MESSAGE', sessionId, timestamp: ts,
                payload: {
                    fromSessionId: (meta.fromSessionId as string) ?? '',
                    content: (raw.content as string) ?? '',
                    messageId: (meta.messageId as string) ?? '',
                    stepId: meta.stepId as string | undefined,
                    teamId: meta.teamId as string | undefined,
                },
            };
        }
        case 'TOOL_OUTPUT_CHUNK':
            return {
                type: 'TOOL_OUTPUT_CHUNK', sessionId, timestamp: ts,
                payload: {
                    toolCallId: (raw.toolCallId as string) ?? '',
                    content: (raw.content as string) ?? '',
                },
            };
        case 'TOOL_PROGRESS': {
            const meta = (raw.resultMetadata as Record<string, unknown>) ?? {};
            return {
                type: 'TOOL_PROGRESS', sessionId, timestamp: ts,
                payload: {
                    toolCallId: (raw.toolCallId as string) ?? '',
                    toolName: (raw.toolName as string) ?? '',
                    phase: ((meta.phase as string) ?? 'EXECUTING') as 'EXECUTING' | 'AWAITING_APPROVAL' | 'STREAMING',
                    elapsedMs: (meta.elapsedMs as number) ?? 0,
                } satisfies import('@/types/agent').ToolProgressPayload,
            };
        }
        case 'SKILL_ACTIVATED': {
            const meta = (raw.resultMetadata as Record<string, unknown>) ?? {};
            return {
                type: 'SKILL_ACTIVATED', sessionId, timestamp: ts,
                payload: {
                    skills: (meta.skills as string[]) ?? [],
                    scores: (meta.scores as number[]) ?? [],
                },
            };
        }
        default:
            // Silently ignore known keepalive/internal event types
            if (type === 'HEARTBEAT') {
                return { type: 'HEARTBEAT' as any, sessionId, timestamp: ts, payload: {} };
            }
            return {
                type: 'AGENT_ERROR', sessionId, timestamp: ts,
                payload: { message: `Unknown event type: ${type}` },
            };
    }
}

interface UseAgentWebSocketOptions {
    onRawMessage?: (data: string) => void;
}

interface UseAgentWebSocketReturn {
    isConnected: boolean;
    isThinking: boolean;
    connectionStatus: ConnectionStatus;
    connect: () => void;
    disconnect: () => void;
    /** Generic send — pass any JSON-serializable payload over the shared WS. */
    send: (payload: Record<string, unknown>) => boolean;
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
    /** Generic send — pass any JSON payload over the shared WS. */
    sendAction: (payload: Record<string, unknown>) => boolean;
}

export function useAgentWebSocket(
    onEvent: (event: AgentEvent) => void,
    options?: UseAgentWebSocketOptions,
): UseAgentWebSocketReturn {
    const [isConnected, setIsConnected] = useState(false);
    const [isThinking, setIsThinking] = useState(false);
    const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>('disconnected');

    const wsRef = useRef<WebSocket | null>(null);
    const reconnectAttemptsRef = useRef(0);
    const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const stalledTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const keepaliveRef = useRef<ReturnType<typeof setInterval> | null>(null);
    const onEventRef = useRef(onEvent);
    const sessionIdRef = useRef<string | null>(null);
    const createPendingRef = useRef<{
        resolve: (sid: string) => void;
        reject: (err: Error) => void;
        timer: ReturnType<typeof setTimeout>;
        mode: import('@store/sessionModeStore').SessionMode;
    } | null>(null);

    const onRawMessageRef = useRef(options?.onRawMessage);
    useEffect(() => { onEventRef.current = onEvent; }, [onEvent]);
    useEffect(() => { onRawMessageRef.current = options?.onRawMessage; }, [options?.onRawMessage]);

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
            // Never auto-cancel an active experts/team run: those are legitimately long and go
            // quiet during a worker's model turn (tool calls stream as TEAM_EVENTs, not chat
            // events). Auto-cancelling here was killing multi-expert runs mid-execution. The
            // visual StallIndicator + manual Force Cancel still cover a genuinely dead session.
            const teamId = useExpertTeamStore.getState().canvasTeamId;
            const teamStatus = teamId
                ? useExpertTeamStore.getState().teams[teamId]?.status
                : undefined;
            const expertsActive = teamStatus != null
                && teamStatus !== 'completed' && teamStatus !== 'failed' && teamStatus !== 'timeout';
            if (expertsActive) {
                return;
            }
            // Never auto-cancel during plan mode: the model can think for minutes
            // while composing a plan, producing no events. 10s is far too short.
            const planActive = useBuildPhaseStore.getState().phase === 'PLAN_PENDING'
                || useBuildPhaseStore.getState().phase === 'EXECUTING';
            if (planActive) {
                return;
            }
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
        // Forward raw message to external handlers (e.g. expert team)
        onRawMessageRef.current?.(data);

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
            if (typeof raw.isGit === 'boolean') {
                useBuildPhaseStore.getState().setIsGit(raw.isGit);
            }
            const pending = createPendingRef.current;
            if (pending) {
                clearTimeout(pending.timer);
                createPendingRef.current = null;
                useSessionModeStore.getState().setSessionMode(sid, pending.mode);
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
        // Non-AgentEvent envelopes consumed elsewhere: TEAM_EVENT is handled by the expert-team
        // raw handler (onRawMessage above); ACK is a command acknowledgement. Skip them so they
        // don't fall through to the "Unknown event type" error path.
        if (type === 'SUBAGENT_EVENT' || type === 'TEAM_EVENT' || type === 'ACK') {
            if (type === 'SUBAGENT_EVENT') {
                clearStalledTimer();
                const meta = (raw.resultMetadata ?? {}) as Record<string, unknown>;
                onEventRef.current({
                    type: 'SUBAGENT_EVENT',
                    sessionId: (raw.sessionId as string) ?? '',
                    timestamp: (raw.timestamp as number) ?? Date.now(),
                    payload: {
                        taskId: (meta.taskId as string) ?? '',
                        taskDescription: (meta.taskDescription as string) ?? '',
                        childEventType: (meta.childEventType as string) ?? '',
                        childToolName: (raw.toolName as string) ?? (meta.childToolName as string) ?? '',
                        childIsError: (meta.childIsError as boolean) ?? false,
                    },
                } as any);
                return;
            }
            // A streaming TEAM_EVENT proves the session is alive — during experts execution the
            // chat channel emits no AGENT_THINKING/TEXT_CHUNK/TOOL_CALL (those are team events),
            // so without this the stalled-probe would wrongly cancel a long-running expert step.
            // It must ALSO refresh lastEventAt: the visual StallIndicator keys off lastEventAt, which
            // is only updated by AgentEvents routed through onEvent below. TEAM_EVENTs return early,
            // so without this the indicator wrongly reports "agent silent / likely stalled" while an
            // expert worker is actively running tools.
            if (type === 'TEAM_EVENT') {
                clearStalledTimer();
                const teamSid = raw.sessionId as string | undefined;
                if (teamSid) useSessionStore.getState().recordEventFor(teamSid, Date.now());
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
        if (keepaliveRef.current) {
            clearInterval(keepaliveRef.current);
            keepaliveRef.current = null;
        }
        const ws = wsRef.current;
        if (!ws) return;
        wsRef.current = null;
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

            // Keepalive ping every 25s to prevent proxy/server idle timeout.
            // The backend AgentWebSocketHandler silently ignores unknown actions.
            if (keepaliveRef.current) clearInterval(keepaliveRef.current);
            keepaliveRef.current = setInterval(() => {
                if (ws.readyState === WebSocket.OPEN) {
                    ws.send(JSON.stringify({ action: 'ping' }));
                }
            }, 25_000);
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
            if (keepaliveRef.current) {
                clearInterval(keepaliveRef.current);
                keepaliveRef.current = null;
            }
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
            // A new user message supersedes any prior stop — clear the resumable flag so the
            // general-flow "Resume" affordance does not linger across a fresh turn.
            useSessionStore.getState().setResumableFor(sessionId, false);
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
            // Stop is user-initiated; the backend interrupt does not always emit a terminal
            // client event (an in-flight bash, for instance, only unblocks later). Flip
            // running off optimistically so the UI leaves the "running" state immediately.
            // Only mark the session resumable when it was ACTUALLY running — otherwise the
            // backend leaves the phase unchanged (resumeSession returns false, no SESSION_RESUMED),
            // and an always-on resumable flag would leave a dead "Resume" button that never clears.
            const store = useSessionStore.getState();
            const wasRunning = store.sessions[sessionId]?.running ?? false;
            store.setRunningFor(sessionId, false);
            if (wasRunning) store.setResumableFor(sessionId, true);
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
                        const mode = useSessionModeStore.getState().pendingMode;
                        createPendingRef.current = { resolve, reject, timer, mode };
                        send({ action: 'create', workspaceId, mode });
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
        send,
        sendMessage,
        approveTool,
        stopAgent,
        createSession,
        bindSession,
        switchSession,
        sendAction: send,
    };
}
