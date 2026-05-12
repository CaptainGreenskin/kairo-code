import { useCallback, type MutableRefObject } from 'react';
import { useSessionStore } from '@store/sessionStore';
import { streamingStore } from '@store/streamingStore';
import { usePreferencesStore, shouldAutoApprove } from '@store/preferencesStore';
import { usePlanModeStore } from '@store/planModeStore';
import type { AgentEvent, ToolCall, SessionRestoredPayload, TodosUpdatedPayload } from '@/types/agent';
import type { Phase } from '@components/ThinkingIndicator';
import type { ToastMessage } from '@components/Toast';
import { deleteSnapshot, saveSnapshot, setLastSessionId } from '@utils/sessionSnapshot';
import { getSessionName } from '@utils/sessionNames';
import { clearMessages as clearCachedMessages } from '@utils/messageCache';

function generateId(): string {
    return crypto.randomUUID();
}

export interface UseAgentEventHandlerArgs {
    /** Per-session map of the currently-streaming assistant message id. Multiple sessions
     *  can stream concurrently, so this must be keyed by sessionId — a single ref would
     *  cross-pollinate when the user switches tabs mid-stream. */
    assistantMsgRef: MutableRefObject<Record<string, string | null>>;
    hasErrorRef: MutableRefObject<boolean>;
    compactionTimerRef: MutableRefObject<ReturnType<typeof setTimeout> | null>;
    setStreamingMsgId: (id: string | null) => void;
    setAgentPhase: (phase: Phase) => void;
    setCurrentToolName: (name: string | undefined) => void;
    setIsCompacting: (v: boolean) => void;
    setShowSettings: (v: boolean) => void;
    setLoadingSessionId: (sid: string | null) => void;
    addToast: (type: ToastMessage['type'], message: string, duration?: number) => void;
    refreshPersistedSessions: () => void;
    trackToolCall: (toolCall: ToolCall) => void;
    /** Send approve/reject decision to the backend WebSocket. Used by auto-approval modes. */
    approveTool: (
        sessionId: string,
        toolCallId: string,
        approved: boolean,
        reason?: string,
        editedArgs?: Record<string, unknown>,
    ) => void;
}

/**
 * Routes incoming AgentEvents into store mutations + local UI state.
 * Extracted from App.tsx to keep the App component readable.
 *
 * Every mutation is keyed off `event.sessionId` so background tabs continue to
 * accumulate state correctly when their session emits events while the user is
 * looking at another tab.
 */
export function useAgentEventHandler(args: UseAgentEventHandlerArgs) {
    const {
        assistantMsgRef, hasErrorRef, compactionTimerRef,
        setStreamingMsgId, setAgentPhase, setCurrentToolName, setIsCompacting,
        setShowSettings, setLoadingSessionId,
        addToast, refreshPersistedSessions, trackToolCall,
        approveTool,
    } = args;

    const {
        ensureSession,
        addMessageTo, setMessagesFor, addToolCallTo, updateToolCallIn,
        setThinkingFor, setTokenUsageFor, setEstimatedCostFor,
        restoreSessionAs, setActiveSession, clearMessagesFor,
        appendThinkingTextTo, clearThinkingTextFor, setMessageThinkingIn,
        setTodosFor, setRunningFor, recordEventFor,
    } = useSessionStore();

    return useCallback(
        (event: AgentEvent) => {
            const sid = event.sessionId;
            // Make sure the per-session entry exists before any per-session mutator
            // tries to read/patch it. Cheap no-op when already present.
            if (sid) ensureSession(sid);
            // Diagnostics: every backend event refreshes lastEventAt and (for non-terminal
            // events) confirms running=true. Terminal cases below flip running back to false.
            // Powers the dev diagnostics panel + stall indicator without coupling to isThinking.
            if (sid) {
                recordEventFor(sid, event.timestamp ?? Date.now());
                if (event.type !== 'AGENT_DONE' && event.type !== 'AGENT_ERROR') {
                    setRunningFor(sid, true);
                }
            }
            // Helper: only update the streaming msg id mirror when this event is for
            // the active tab — otherwise we'd flicker the indicator when a background
            // session ticks.
            const isActive = sid != null && sid === useSessionStore.getState().activeSessionId;
            const getActiveMsgId = () => (sid ? assistantMsgRef.current[sid] ?? null : null);
            const setActiveMsgId = (id: string | null) => {
                if (!sid) return;
                assistantMsgRef.current[sid] = id;
                if (isActive) setStreamingMsgId(id);
            };

            switch (event.type) {
                case 'TEXT_CHUNK': {
                    const text = (event.payload as { text: string }).text;
                    if (isActive) {
                        setAgentPhase('writing');
                        setCurrentToolName(undefined);
                    }
                    // Capture reasoning buffer BEFORE clearing so it can be pinned onto
                    // the assistant message that's about to start (or continue).
                    const sessionState = useSessionStore.getState().sessions[sid];
                    const pendingThinking = sessionState?.thinkingText ?? '';
                    clearThinkingTextFor(sid);
                    // Boundary: if the current assistant message already has tool calls,
                    // any new text begins a fresh bubble. Without this, text emitted
                    // across multiple agent iterations concatenates into one giant
                    // message (the "5 duplicate tables" bug).
                    const currentMsgId = getActiveMsgId();
                    if (currentMsgId) {
                        const cur = (sessionState?.messages ?? []).find((m) => m.id === currentMsgId);
                        if (cur && (cur.toolCalls ?? []).length > 0) {
                            const pending = streamingStore.getContentDeduped(sid);
                            if (pending) {
                                const msgs = useSessionStore.getState().sessions[sid]?.messages ?? [];
                                setMessagesFor(
                                    sid,
                                    msgs.map((m) =>
                                        m.id === currentMsgId
                                            ? { ...m, content: pending }
                                            : m,
                                    ),
                                );
                            }
                            streamingStore.clear(sid);
                            setActiveMsgId(null);
                        }
                    }
                    if (!getActiveMsgId()) {
                        const msgId = generateId();
                        setActiveMsgId(msgId);
                        addMessageTo(sid, {
                            id: msgId,
                            role: 'assistant',
                            content: '',
                            toolCalls: [],
                            timestamp: Date.now(),
                            thinking: pendingThinking || undefined,
                        });
                    } else if (pendingThinking) {
                        setMessageThinkingIn(sid, getActiveMsgId()!, pendingThinking);
                    }
                    streamingStore.append(sid, text, isActive);
                    break;
                }

                case 'TOOL_CALL': {
                    const payload = event.payload as {
                        toolCallId: string;
                        toolName: string;
                        input: Record<string, unknown>;
                        requiresApproval: boolean;
                    };
                    if (isActive) {
                        setAgentPhase('tool');
                        setCurrentToolName(payload.toolName);
                    }
                    // Capture reasoning before clearing so it can be pinned onto the
                    // assistant message that owns this tool call.
                    const pendingThinking =
                        useSessionStore.getState().sessions[sid]?.thinkingText ?? '';
                    clearThinkingTextFor(sid);
                    let curMsgId = getActiveMsgId();
                    if (!curMsgId) {
                        const msgId = generateId();
                        setActiveMsgId(msgId);
                        curMsgId = msgId;
                        addMessageTo(sid, {
                            id: msgId,
                            role: 'assistant',
                            content: '',
                            toolCalls: [],
                            timestamp: Date.now(),
                            thinking: pendingThinking || undefined,
                        });
                    } else if (pendingThinking) {
                        setMessageThinkingIn(sid, curMsgId, pendingThinking);
                    }
                    // Auto-approval policy: bypass the pending state when the user has
                    // chosen yolo / auto-safe mode. The card still renders, but with
                    // status='approved' so the agent loop is unblocked immediately.
                    const approvalMode = usePreferencesStore.getState().approvalMode;
                    const willAutoApprove =
                        payload.requiresApproval && shouldAutoApprove(approvalMode, payload.toolName);

                    const toolCall: ToolCall = {
                        id: payload.toolCallId,
                        toolName: payload.toolName,
                        input: payload.input,
                        status: payload.requiresApproval && !willAutoApprove ? 'pending' : 'approved',
                        requiresApproval: payload.requiresApproval,
                    };
                    addToolCallTo(sid, curMsgId, toolCall);
                    trackToolCall(toolCall);

                    // Mirror plan-mode state from observed plan tool calls. enter_plan_mode is
                    // READ_ONLY (auto-runs, no approval) → backend has flipped plan mode on.
                    // exit_plan_mode requests approval first → frontend reveals plan modal.
                    if (payload.toolName === 'enter_plan_mode') {
                        usePlanModeStore.getState().setActive(true);
                        usePlanModeStore.getState().setPending(false);
                    } else if (payload.toolName === 'exit_plan_mode' && payload.requiresApproval) {
                        usePlanModeStore.getState().setAwaitingApproval(true);
                    }

                    if (willAutoApprove) {
                        approveTool(sid, payload.toolCallId, true);
                    }
                    break;
                }

                case 'TOOL_RESULT': {
                    const payload = event.payload as {
                        toolCallId: string;
                        result: string;
                        isError: boolean;
                        durationMs: number;
                        resultMetadata?: Record<string, unknown>;
                    };
                    if (isActive) {
                        setAgentPhase('thinking');
                        setCurrentToolName(undefined);
                    }
                    const sessionMsgs =
                        useSessionStore.getState().sessions[sid]?.messages ?? [];
                    const targetMsg = sessionMsgs.find((m) =>
                        (m.toolCalls ?? []).some((tc) => tc.id === payload.toolCallId),
                    );
                    if (targetMsg) {
                        const tc = (targetMsg.toolCalls ?? []).find((c) => c.id === payload.toolCallId);
                        // Plan-mode state transitions on tool completion.
                        if (tc?.toolName === 'exit_plan_mode' && !payload.isError) {
                            // Backend flipped plan mode off if approval succeeded; if rejected the
                            // result body says "Still in Plan Mode" so we keep `active` true.
                            const stillInPlan = (payload.result || '').includes('Still in Plan Mode');
                            usePlanModeStore.getState().setAwaitingApproval(false);
                            if (!stillInPlan) {
                                usePlanModeStore.getState().setActive(false);
                            }
                        }
                        const rawReason = payload.resultMetadata?.['failureReason'];
                        const failureReason =
                            typeof rawReason === 'string'
                                ? (rawReason as
                                      | 'TIMEOUT'
                                      | 'USER_CANCELLED'
                                      | 'INTERRUPTED'
                                      | 'HANDLER_ERROR'
                                      | 'VALIDATION')
                                : undefined;
                        updateToolCallIn(sid, targetMsg.id, payload.toolCallId, {
                            result: payload.result,
                            status: 'done',
                            durationMs: payload.durationMs,
                            isError: payload.isError,
                            failureReason,
                            // Heartbeat is over — clear progress fields so the card stops showing live elapsed.
                            progressPhase: undefined,
                            progressElapsedMs: undefined,
                        });
                    }
                    break;
                }

                case 'TOOL_PROGRESS': {
                    const payload = event.payload as {
                        toolCallId: string;
                        toolName: string;
                        phase: 'EXECUTING' | 'AWAITING_APPROVAL' | 'STREAMING';
                        elapsedMs: number;
                    };
                    const msgs = useSessionStore.getState().sessions[sid]?.messages ?? [];
                    const targetMsg = msgs.find((m) =>
                        (m.toolCalls ?? []).some((tc) => tc.id === payload.toolCallId),
                    );
                    if (targetMsg) {
                        updateToolCallIn(sid, targetMsg.id, payload.toolCallId, {
                            progressPhase: payload.phase,
                            progressElapsedMs: payload.elapsedMs,
                        });
                    }
                    break;
                }

                case 'AGENT_DONE': {
                    const payload = event.payload as {
                        inputTokens: number;
                        outputTokens: number;
                    };
                    // Safety net: any tool call still 'approved' (Running) at agent-done
                    // never received a TOOL_RESULT. Mark them errored so the UI doesn't
                    // pin a forever-spinning card. Real backend hangs still need fixing.
                    {
                        const msgs = useSessionStore.getState().sessions[sid]?.messages ?? [];
                        for (const m of msgs) {
                            for (const tc of m.toolCalls ?? []) {
                                if (tc.status === 'approved') {
                                    updateToolCallIn(sid, m.id, tc.id, {
                                        status: 'error',
                                        result: '(no result — agent ended before tool returned)',
                                        isError: true,
                                    });
                                }
                            }
                        }
                    }
                    const curMsgId = getActiveMsgId();
                    if (curMsgId) {
                        const content = streamingStore.getContentDeduped(sid);
                        if (content) {
                            const msgs = useSessionStore.getState().sessions[sid]?.messages ?? [];
                            setMessagesFor(
                                sid,
                                msgs.map((m) =>
                                    m.id === curMsgId
                                        ? { ...m, content }
                                        : m,
                                ),
                            );
                        }
                        // Drain any trailing reasoning that didn't get pinned by an
                        // intervening TEXT_CHUNK/TOOL_CALL (rare: pure reasoning runs).
                        const trailingThinking =
                            useSessionStore.getState().sessions[sid]?.thinkingText ?? '';
                        if (trailingThinking) {
                            setMessageThinkingIn(sid, curMsgId, trailingThinking);
                        }
                        streamingStore.clear(sid);
                    }
                    clearThinkingTextFor(sid);
                    setActiveMsgId(null);
                    setThinkingFor(sid, false);
                    setRunningFor(sid, false);
                    if (isActive) {
                        setAgentPhase('thinking');
                        setCurrentToolName(undefined);
                    }
                    setTokenUsageFor(sid, {
                        input: payload.inputTokens,
                        output: payload.outputTokens,
                    });
                    const cost =
                        (payload.inputTokens * 0.001 + payload.outputTokens * 0.003) /
                        1000;
                    setEstimatedCostFor(sid, cost);
                    {
                        const msgs = useSessionStore.getState().sessions[sid]?.messages ?? [];
                        if (sid && msgs.length > 0) {
                            const name = getSessionName(sid)
                                ?? `Session ${sid.slice(0, 8)}`;
                            saveSnapshot(sid, name, msgs).then((ok) => {
                                if (ok) {
                                    setLastSessionId(sid);
                                    refreshPersistedSessions();
                                }
                            });
                        }
                    }
                    break;
                }

                case 'AGENT_ERROR': {
                    const payload = event.payload as { message: string; errorType?: string };
                    // Same safety net as AGENT_DONE — finalize stuck-running tool cards.
                    {
                        const msgs = useSessionStore.getState().sessions[sid]?.messages ?? [];
                        for (const m of msgs) {
                            for (const tc of m.toolCalls ?? []) {
                                if (tc.status === 'approved' || tc.status === 'pending') {
                                    updateToolCallIn(sid, m.id, tc.id, {
                                        status: 'error',
                                        result: '(no result — agent ended before tool returned)',
                                        isError: true,
                                    });
                                }
                            }
                        }
                    }
                    setActiveMsgId(null);
                    setThinkingFor(sid, false);
                    setRunningFor(sid, false);
                    if (isActive) {
                        setAgentPhase('thinking');
                        setCurrentToolName(undefined);
                        setLoadingSessionId(null);
                    }
                    if (payload.errorType === 'SESSION_NOT_FOUND') {
                        // Server forgot this session (typically a restart). Wipe every layer
                        // that could resurrect zombie tool cards: in-memory store, the localStorage
                        // message cache, and the on-disk snapshot. Without this the next reload
                        // re-renders the dead "Pending" approvals from cache.
                        const deadId = sid;
                        if (deadId) {
                            clearMessagesFor(deadId);
                            clearCachedMessages(deadId);
                            deleteSnapshot(deadId).catch(() => {});
                        }
                        if (isActive) {
                            setActiveSession(null);
                            sessionStorage.removeItem('kairo-code-session-id');
                        }
                        addToast('warning', 'Session expired, please create a new session');
                        break;
                    }
                    hasErrorRef.current = true;

                    const errorType = payload.errorType ?? '';
                    switch (errorType) {
                        case 'AUTH_FAILURE':
                            setShowSettings(true);
                            addToast('error', 'API key invalid — check Settings');
                            break;
                        case 'RATE_LIMITED':
                            addToast('warning', 'Rate limited — retry in a moment');
                            break;
                        case 'QUOTA_EXCEEDED':
                            addToast('error', 'API quota exceeded');
                            break;
                        case 'PROVIDER_ERROR':
                            addToast('error', `Provider error: ${payload.message}`);
                            break;
                        default:
                            addToast('error', payload.message);
                            break;
                    }

                    addMessageTo(sid, {
                        id: generateId(),
                        role: 'error',
                        content: payload.message,
                        toolCalls: [],
                        timestamp: Date.now(),
                    });
                    break;
                }

                case 'AGENT_THINKING': {
                    const payload = event.payload as { isThinking: boolean; text?: string };
                    setThinkingFor(sid, payload.isThinking);
                    if (payload.isThinking) {
                        setActiveMsgId(null);
                        if (isActive) {
                            setAgentPhase('thinking');
                            setCurrentToolName(undefined);
                        }
                    }
                    // Streaming reasoning_content delta from thinking models. We always
                    // append (even when isThinking=false hasn't been set yet) — backend
                    // sends one-shot AGENT_THINKING events without text, then a stream of
                    // text-bearing events while reasoning_content is flowing.
                    if (payload.text) {
                        appendThinkingTextTo(sid, payload.text);
                    }
                    break;
                }

                case 'SESSION_RESTORED': {
                    const payload = event.payload as SessionRestoredPayload;
                    // Server's bindSession reads {workingDir}/.kairo-session/checkpoint.json,
                    // which can be missing or stale for sessions whose workspace has never
                    // run an agent locally. Don't let an empty payload clobber messages we
                    // already hydrated from cache or the per-session snapshot — only
                    // overwrite when the server has actual history to contribute.
                    if (payload.messages && payload.messages.length > 0) {
                        restoreSessionAs(sid, payload.messages, payload.running, payload.todos);
                    } else {
                        setThinkingFor(sid, payload.running);
                        if (payload.todos) setTodosFor(sid, payload.todos);
                    }
                    setRunningFor(sid, payload.running);
                    streamingStore.clear(sid);
                    if (isActive) setLoadingSessionId(null);
                    break;
                }

                case 'TODOS_UPDATED': {
                    const payload = event.payload as TodosUpdatedPayload;
                    setTodosFor(sid, payload.todos ?? []);
                    break;
                }

                case 'CONTEXT_COMPACTED': {
                    if (isActive) {
                        setIsCompacting(true);
                        if (compactionTimerRef.current) clearTimeout(compactionTimerRef.current);
                        compactionTimerRef.current = setTimeout(() => {
                            setIsCompacting(false);
                            compactionTimerRef.current = null;
                        }, 3000);
                    }
                    break;
                }
            }
        },
        [
            ensureSession,
            addMessageTo,
            setMessagesFor,
            addToolCallTo,
            updateToolCallIn,
            setThinkingFor,
            appendThinkingTextTo,
            clearThinkingTextFor,
            setMessageThinkingIn,
            setTokenUsageFor,
            setEstimatedCostFor,
            restoreSessionAs,
            setActiveSession,
            clearMessagesFor,
            setTodosFor,
            setRunningFor,
            recordEventFor,
            setStreamingMsgId,
            setAgentPhase,
            setCurrentToolName,
            setIsCompacting,
            setShowSettings,
            setLoadingSessionId,
            addToast,
            refreshPersistedSessions,
            trackToolCall,
            approveTool,
            assistantMsgRef,
            hasErrorRef,
            compactionTimerRef,
        ],
    );
}
