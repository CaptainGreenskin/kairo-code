import { useCallback, type MutableRefObject } from 'react';
import { useSessionStore } from '@store/sessionStore';
import { streamingStore } from '@store/streamingStore';
import { usePreferencesStore, shouldAutoApprove } from '@store/preferencesStore';
import { usePlanModeStore } from '@store/planModeStore';
import type { AgentEvent, ToolCall, PlanReadyPayload, SessionRestoredPayload, TodosUpdatedPayload, ContextCompactedPayload } from '@/types/agent';
import { useBuildPhaseStore } from '@store/buildPhaseStore';
import { useExpertTeamStore } from '@store/expertTeamStore';
import { useLayoutStore } from '@store/layoutStore';
import { useOpenFilesStore } from '@store/openFilesStore';
import { useQueueStore } from '@store/queueStore';
import type { Phase } from '@components/ThinkingIndicator';
import type { ToastMessage } from '@components/Toast';
import { deleteSnapshot, saveSnapshot, setLastSessionId } from '@utils/sessionSnapshot';
import { getSessionName } from '@utils/sessionNames';
import { clearMessages as clearCachedMessages } from '@utils/messageCache';
import { useEvolutionStore } from '@store/evolutionStore';

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
        setTodosFor, setRunningFor, setResumableFor, recordEventFor,
        setLastIterationFor,
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
                if (event.type !== 'AGENT_DONE' && event.type !== 'AGENT_ERROR'
                    && (event.type as string) !== 'HEARTBEAT') {
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

                    // Intercept <evolution-event> — toast + evolution store update
                    if (text.includes('<evolution-event')) {
                        const evoStore = useEvolutionStore.getState();
                        if (text.includes('type="review_starting"')) {
                            evoStore.setReviewing(true);
                            addToast('info', '🧬 Self-evolution review starting...', 5000);
                        } else if (text.includes('type="review_complete"')) {
                            evoStore.setReviewing(false);
                        } else if (text.includes('type="skill_created"')) {
                            evoStore.setReviewing(false);
                            evoStore.incrementSkillCount();
                            const nameMatch = text.match(/name="([^"]+)"/);
                            addToast('info',
                                `✨ New skill learned: ${nameMatch?.[1] ?? 'unknown'}`, 8000);
                        }
                        break;
                    }

                    // Intercept <task-notification> XML — render as a card, not raw text
                    const tnMatch = text.match(
                        /<task-notification\s+task_id="([^"]*?)"\s+description="([^"]*?)"\s+status="([^"]*?)"\s+duration_ms="([^"]*?)">\s*([\s\S]*?)\s*<\/task-notification>/
                    );
                    if (tnMatch) {
                        const [, taskId, desc, tnStatus, durationStr, result] = tnMatch;
                        const durationMs = parseInt(durationStr, 10) || 0;

                        // Update the corresponding SubagentCard from 'running' to 'done'
                        const allMsgs = useSessionStore.getState().sessions[sid]?.messages ?? [];
                        for (const msg of allMsgs) {
                            for (const tc of msg.toolCalls ?? []) {
                                if (tc.toolName === 'task' && tc.status === 'approved') {
                                    const tcTaskId = tc.resultMetadata?.['task.id'] as string
                                        ?? (typeof tc.result === 'string' && tc.result.match(/task_id="([^"]+)"/)?.[1]);
                                    if (tcTaskId === taskId) {
                                        updateToolCallIn(sid, msg.id, tc.id, {
                                            status: 'done',
                                            durationMs,
                                            isError: tnStatus === 'failed',
                                        });
                                    }
                                }
                            }
                        }

                        addMessageTo(sid, {
                            id: generateId(),
                            role: 'assistant',
                            content: '',
                            toolCalls: [],
                            timestamp: Date.now(),
                            kind: 'taskNotification',
                            taskNotificationMeta: {
                                taskId,
                                description: desc,
                                status: tnStatus as 'completed' | 'failed',
                                durationMs,
                                result: result.trim(),
                            },
                        });
                        break;
                    }

                    if (isActive) {
                        // Don't override 'waiting' phase when agent emits text while
                        // background workers are still running — keeps the indicator visible.
                        const allSessionMsgs = useSessionStore.getState().sessions[sid]?.messages ?? [];
                        const hasRunningTasks = allSessionMsgs.some(m =>
                            (m.toolCalls ?? []).some(tc => tc.toolName === 'task' && tc.status === 'approved'));
                        setAgentPhase(hasRunningTasks ? 'waiting' : 'writing');
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
                        createdAt: Date.now(),
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
                        let resolvedDuration = payload.durationMs;
                        if ((!resolvedDuration || resolvedDuration <= 0) && tc?.createdAt) {
                            resolvedDuration = Date.now() - tc.createdAt;
                        }
                        // Async task tool: keep status as 'approved' (renders as running
                        // in SubagentCard) until the real task-notification arrives.
                        const isAsyncTaskLaunch =
                            tc?.toolName === 'task' &&
                            (payload.resultMetadata?.['task.status'] === 'async_launched' ||
                             (typeof payload.result === 'string' &&
                              payload.result.includes('status="running"')));
                        updateToolCallIn(sid, targetMsg.id, payload.toolCallId, {
                            result: payload.result,
                            status: isAsyncTaskLaunch ? 'approved' : 'done',
                            durationMs: resolvedDuration,
                            isError: payload.isError,
                            failureReason,
                            progressPhase: undefined,
                            progressElapsedMs: undefined,
                            resultMetadata: payload.resultMetadata,
                        });
                        // Show "waiting for workers" indicator when async tasks are running
                        if (isAsyncTaskLaunch && isActive) {
                            setAgentPhase('waiting');
                        }
                        // Auto-refresh file tree when a write/edit tool completes successfully.
                        if (tc && !payload.isError) {
                            const name = tc.toolName.toLowerCase();
                            if (name.includes('write') || name.includes('edit') || name.includes('patch') || name.includes('create') || name.includes('replace') || name.includes('insert')) {
                                const input = tc.input as Record<string, unknown> | undefined;
                                const path = (input?.file_path ?? input?.path ?? input?.filePath) as string | undefined;
                                const paths: string[] = [];
                                if (path) paths.push(path);
                                const files = input?.files as Array<{path?: string}> | undefined;
                                if (Array.isArray(files)) {
                                    files.forEach(f => { if (f.path) paths.push(f.path); });
                                }
                                if (paths.length > 0) {
                                    useLayoutStore.getState().bumpFileTreeRefresh(paths);
                                    const codeExts = /\.(java|py|ts|tsx|js|jsx|json|md|yml|yaml|xml|html|css|sh)$/;
                                    paths.filter(p => codeExts.test(p)).slice(0, 3).forEach(p => {
                                        useOpenFilesStore.getState().openFile(p, undefined, true);
                                    });
                                }
                            }
                        }
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

                case 'SUBAGENT_EVENT' as any: {
                    const payload = event.payload as {
                        taskId: string;
                        taskDescription: string;
                        childEventType: string;
                        childToolName: string;
                        childIsError: boolean;
                    };
                    const msgs = useSessionStore.getState().sessions[sid]?.messages ?? [];
                    for (const m of msgs) {
                        const tc = (m.toolCalls ?? []).find(
                            (t) => t.toolName === 'task' && (t.status === 'approved' || t.status === 'pending'),
                        );
                        if (tc) {
                            const newEvent = {
                                childEventType: payload.childEventType as 'TOOL_CALL' | 'TOOL_RESULT',
                                childToolName: payload.childToolName,
                                childIsError: payload.childIsError,
                                timestamp: Date.now(),
                            };
                            const existing = tc.subagentEvents ?? [];
                            updateToolCallIn(sid, m.id, tc.id, {
                                subagentEvents: [...existing, newEvent],
                            });
                            break;
                        }
                    }
                    break;
                }

                case 'TOOL_OUTPUT_CHUNK': {
                    const payload = event.payload as {
                        toolCallId: string;
                        content: string;
                    };
                    const msgs = useSessionStore.getState().sessions[sid]?.messages ?? [];
                    const targetMsg = msgs.find((m) =>
                        (m.toolCalls ?? []).some((tc) => tc.id === payload.toolCallId),
                    );
                    if (targetMsg) {
                        const tc = (targetMsg.toolCalls ?? []).find((c) => c.id === payload.toolCallId);
                        const existing = tc?.partialOutput ?? '';
                        updateToolCallIn(sid, targetMsg.id, payload.toolCallId, {
                            partialOutput: existing + payload.content,
                        });
                    }
                    break;
                }

                case 'MESSAGE_QUEUED': {
                    // Queue count and message marking are handled client-side in handleSend.
                    // Backend event is a confirmation — only increment if client missed it.
                    break;
                }

                case 'AGENT_DONE': {
                    const payload = event.payload as {
                        inputTokens: number;
                        outputTokens: number;
                        cost?: number;
                    };
                    // Transition build phase on agent completion during EXECUTING
                    if (useBuildPhaseStore.getState().phase === 'EXECUTING') {
                        useBuildPhaseStore.getState().setPhase('COMPLETED');
                    }
                    // Safety net: any tool call still 'approved' (Running) at agent-done
                    // never received a TOOL_RESULT. Mark them errored so the UI doesn't
                    // pin a forever-spinning card. Real backend hangs still need fixing.
                    //
                    // Skip tools that already have a result — happens for exit_plan_mode,
                    // which the PlanPendingInterceptHook SKIPs server-side: a synthetic
                    // TOOL_RESULT arrives and sets status='done'/result='Tool execution
                    // skipped by hook', then the user's Approve click optimistically flips
                    // status='approved' (App.tsx handleApproveTool). Without this guard the
                    // safety net would overwrite a tool that *did* return.
                    {
                        const msgs = useSessionStore.getState().sessions[sid]?.messages ?? [];
                        for (const m of msgs) {
                            for (const tc of m.toolCalls ?? []) {
                                if (tc.status === 'approved' && !tc.result) {
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
                    // Use server-provided cost when available; fall back to a blended
                    // estimate (GPT-4o-class pricing) for servers that don't report it.
                    const cost = payload.cost
                        ?? (payload.inputTokens * 2.5 + payload.outputTokens * 10.0) / 1_000_000;
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
                        // Evolution toast: if session had enough iterations, the backend
                        // EvolutionHook fires an async review. Show a toast so the user
                        // knows self-evolution is active.
                        const toolCallCount = msgs.reduce(
                            (acc, m) => acc + (m.toolCalls?.length ?? 0), 0);
                        if (toolCallCount >= 3) {
                            useEvolutionStore.getState().setReviewing(true);
                            addToast('info', '🧬 Self-evolution review in progress...', 6000);
                            setTimeout(() => {
                                useEvolutionStore.getState().setReviewing(false);
                            }, 120_000);
                        }
                    }
                    break;
                }

                case 'AGENT_ERROR': {
                    const payload = event.payload as { message: string; errorType?: string };
                    // Transition build phase on agent error during EXECUTING
                    if (useBuildPhaseStore.getState().phase === 'EXECUTING') {
                        useBuildPhaseStore.getState().setPhase('FAILED_EXECUTION');
                    }
                    // Same safety net as AGENT_DONE — finalize stuck-running tool cards.
                    // Skip tools that already have a result (see AGENT_DONE comment).
                    {
                        const msgs = useSessionStore.getState().sessions[sid]?.messages ?? [];
                        for (const m of msgs) {
                            for (const tc of m.toolCalls ?? []) {
                                if ((tc.status === 'approved' || tc.status === 'pending') && !tc.result) {
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
                        // A queued message just started executing — unhide it in chat
                        if (useQueueStore.getState().count > 0) {
                            useQueueStore.getState().dequeue();
                            if (sid) useSessionStore.getState().clearFirstQueued(sid);
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
                    // Backend is authoritative on resumability across reloads/rebinds: a session
                    // left in a FAILED_* phase restores its "Resume" affordance.
                    setResumableFor(sid, payload.resumable ?? false);
                    streamingStore.clear(sid);
                    if (isActive) setLoadingSessionId(null);

                    // Restore Experts Canvas from sessionStorage if the session
                    // had an active expert team before the page refresh.
                    const savedCanvasTeamId = sessionStorage.getItem('kairo-canvas-team-id');
                    if (savedCanvasTeamId) {
                        const store = useExpertTeamStore.getState();
                        store.setCanvasTeamId(savedCanvasTeamId, sid);
                        const snapshotJson = sessionStorage.getItem('kairo-canvas-team-snapshot');
                        if (snapshotJson) {
                            try {
                                const teamState = JSON.parse(snapshotJson);
                                useExpertTeamStore.setState((prev) => ({
                                    teams: { ...prev.teams, [savedCanvasTeamId]: teamState },
                                }));
                            } catch { /* malformed snapshot — ignore */ }
                        }
                    }
                    break;
                }

                case 'TODOS_UPDATED': {
                    const payload = event.payload as TodosUpdatedPayload;
                    setTodosFor(sid, payload.todos ?? []);
                    break;
                }

                case 'CONTEXT_COMPACTED': {
                    const cp = event.payload as ContextCompactedPayload;
                    addMessageTo(sid, {
                        id: generateId(),
                        role: 'assistant',
                        kind: 'compaction',
                        compactionMeta: { beforeTokens: cp.beforeTokens, maxTokens: cp.maxTokens },
                        content: '',
                        toolCalls: [],
                        timestamp: Date.now(),
                    });
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

                case 'PLAN_READY': {
                    useBuildPhaseStore.getState().setPhase('PLAN_PENDING');
                    const planPayload = event.payload as PlanReadyPayload;
                    if (planPayload.teamId) {
                        useExpertTeamStore.getState().setCanvasTeamId(planPayload.teamId, sid);
                        // Populate DAG immediately from embedded plan data — bypasses the
                        // TeamEventBridge subscription timing issue where the TEAM_EVENT
                        // PLAN_READY fires before the frontend subscribes to that teamId.
                        if (planPayload.steps) {
                            useExpertTeamStore.getState().handleTeamEvent({
                                type: 'TEAM_EVENT',
                                teamId: planPayload.teamId,
                                eventType: 'PLAN_READY',
                                seq: 1,
                                attributes: {
                                    mode: planPayload.mode ?? 'dag',
                                    goal: planPayload.goal,
                                    steps: planPayload.steps,
                                    planId: planPayload.planId,
                                    totalSteps: planPayload.totalSteps,
                                },
                                timestamp: new Date().toISOString(),
                            });
                        }
                    }
                    break;
                }

                case 'REVERTED': {
                    useBuildPhaseStore.getState().setPhase('idle');
                    // Optionally: could clear execution messages here;
                    // parent handles via phase change observation.
                    break;
                }

                case 'CLEAR_EXECUTION_MESSAGES': {
                    const msgs = useSessionStore.getState().sessions[sid]?.messages ?? [];
                    const filtered = msgs.filter(
                        (m) => !m.toolCalls?.length,
                    );
                    if (filtered.length !== msgs.length) {
                        setMessagesFor(sid, filtered);
                    }
                    break;
                }

                case 'MODE_DEMOTED': {
                    // Triage gate decided the message is too short/simple for experts
                    // fanout. Surface as an info-style assistant message (not error red)
                    // so the user sees the hint without it polluting the error pipeline.
                    const payload = event.payload as { reason: string };
                    addMessageTo(sid, {
                        id: generateId(),
                        role: 'assistant',
                        content: payload.reason,
                        toolCalls: [],
                        timestamp: Date.now(),
                    });
                    setActiveMsgId(null);
                    setThinkingFor(sid, false);
                    setRunningFor(sid, false);
                    if (isActive) {
                        setAgentPhase('thinking');
                        setCurrentToolName(undefined);
                        setLoadingSessionId(null);
                    }
                    break;
                }

                case 'MODE_ESCALATED': {
                    const payload = event.payload as { reason: string };
                    addMessageTo(sid, {
                        id: generateId(),
                        role: 'assistant',
                        content: payload.reason,
                        toolCalls: [],
                        timestamp: Date.now(),
                    });
                    break;
                }

                case 'SKILL_ACTIVATED': {
                    const meta = event.payload as { skills?: string[]; scores?: number[] };
                    const skills = meta?.skills ?? [];
                    const scores = meta?.scores ?? [];
                    if (skills.length > 0) {
                        const details = skills.map((s, i) =>
                            `${s} (${Math.round((scores[i] ?? 0) * 100)}%)`
                        ).join(', ');
                        addMessageTo(sid, {
                            id: generateId(),
                            role: 'assistant',
                            content: `✨ **Skills activated**: ${details}`,
                            toolCalls: [],
                            timestamp: Date.now(),
                        });
                    }
                    break;
                }

                case 'ITERATION_ADVANCED': {
                    const it = (event.payload as { iteration?: number }).iteration;
                    if (typeof it === 'number') setLastIterationFor(sid, it);
                    break;
                }

                case 'SESSION_RESUMED': {
                    useBuildPhaseStore.getState().setPhase('idle');
                    setRunningFor(sid, false);
                    setResumableFor(sid, false);
                    addMessageTo(sid, {
                        id: generateId(),
                        role: 'assistant',
                        content: 'Session resumed — you can continue from where you left off.',
                        toolCalls: [],
                        timestamp: Date.now(),
                    });
                    break;
                }

                case 'PEER_MESSAGE': {
                    const payload = event.payload as {
                        fromSessionId: string; content: string; messageId: string;
                        stepId?: string; teamId?: string;
                    };
                    // Step-level events → one collapsible expert card per step (qoder-style),
                    // bound live to expertTeamStore. Upsert by stable id so the lifecycle
                    // events (started/eval/PASS/completed) collapse into a single card that
                    // updates itself. Team-level events (no stepId) carry no per-agent detail
                    // and are redundant with the roster's progress + each card's status, so we
                    // suppress them rather than spamming the thread with "[expert] …" lines.
                    if (payload.stepId && payload.teamId) {
                        const cardId = `step:${payload.teamId}:${payload.stepId}`;
                        const existing = useSessionStore.getState().sessions[sid]?.messages;
                        if (!existing?.some((m) => m.id === cardId)) {
                            addMessageTo(sid, {
                                id: cardId,
                                role: 'assistant',
                                content: '',
                                toolCalls: [],
                                timestamp: Date.now(),
                                kind: 'expertStep',
                                stepRef: { teamId: payload.teamId, stepId: payload.stepId },
                            });
                        }
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
            setResumableFor,
            setLastIterationFor,
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
