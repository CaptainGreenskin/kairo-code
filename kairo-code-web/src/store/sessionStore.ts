import { create } from 'zustand';
import type { Message, ToolCall, TokenUsage, Todo } from '@/types/agent';

/**
 * Per-session state. The store keeps a map of these keyed by sessionId so multiple
 * chat tabs can stay alive simultaneously (Cursor-style). The legacy top-level
 * fields (sessionId, messages, …) mirror the *active* tab so existing consumers
 * that read `useSessionStore((s) => s.messages)` continue to work unchanged.
 */
interface PerSession {
    messages: Message[];
    isThinking: boolean;
    thinkingText: string;
    tokenUsage: TokenUsage;
    estimatedCost: number;
    currentModel: string;
    title?: string;
    /** Latest todo snapshot from the agent's `todo_write` tool. Empty when the agent has not
     *  produced a plan yet. The TodoListPanel renders this directly. */
    todos: Todo[];
    /**
     * True while the backend agent loop is active. Distinct from {@link #isThinking}, which
     * flickers off briefly between iterations whenever the agent flips into tool-execute mode —
     * `running` only flips false when AGENT_DONE / AGENT_ERROR / SESSION_RESTORED(running=false)
     * arrives. Send button + stop button rely on this so they don't bounce mid-iteration.
     */
    running: boolean;
    /** Unix millis of the most recent backend event for this session. 0 if none received yet.
     *  Powers the stall indicator: `Date.now() - lastEventAt > 30s && running` → show warning. */
    lastEventAt: number;
}

const EMPTY_SESSION: PerSession = {
    messages: [],
    isThinking: false,
    thinkingText: '',
    tokenUsage: { input: 0, output: 0 },
    estimatedCost: 0,
    currentModel: '',
    todos: [],
    running: false,
    lastEventAt: 0,
};

interface SessionsState {
    // Multi-session core
    sessions: Record<string, PerSession>;
    openTabs: string[];
    activeSessionId: string | null;

    // Active-session mirror (legacy reads — kept in sync after every mutation)
    sessionId: string | null;
    messages: Message[];
    isThinking: boolean;
    thinkingText: string;
    tokenUsage: TokenUsage;
    estimatedCost: number;
    currentModel: string;
    todos: Todo[];
    running: boolean;
    lastEventAt: number;

    // Tab management
    ensureSession: (sid: string) => void;
    openSession: (sid: string) => void;
    closeSession: (sid: string) => void;
    setActiveSession: (sid: string | null) => void;
    setSessionTitle: (sid: string, title: string) => void;

    // Per-session explicit mutators (event handler uses these with event.sessionId)
    addMessageTo: (sid: string, msg: Message) => void;
    setMessagesFor: (sid: string, msgs: Message[]) => void;
    appendChunkTo: (sid: string, mid: string, text: string) => void;
    addToolCallTo: (sid: string, mid: string, tc: ToolCall) => void;
    updateToolCallIn: (sid: string, mid: string, tcid: string, upd: Partial<ToolCall>) => void;
    setThinkingFor: (sid: string, b: boolean) => void;
    appendThinkingTextTo: (sid: string, text: string) => void;
    clearThinkingTextFor: (sid: string) => void;
    setMessageThinkingIn: (sid: string, mid: string, text: string) => void;
    setTokenUsageFor: (sid: string, u: TokenUsage) => void;
    setEstimatedCostFor: (sid: string, c: number) => void;
    setCurrentModelFor: (sid: string, model: string) => void;
    setTodosFor: (sid: string, todos: Todo[]) => void;
    setRunningFor: (sid: string, running: boolean) => void;
    recordEventFor: (sid: string, ts?: number) => void;
    clearMessagesFor: (sid: string) => void;
    restoreSessionAs: (sid: string, msgs: Message[], running: boolean, todos?: Todo[]) => void;

    // Legacy actions (route to the active session; no-op if no active session)
    setSessionId: (id: string | null) => void;
    addMessage: (message: Message) => void;
    setMessages: (messages: Message[]) => void;
    appendChunk: (messageId: string, text: string) => void;
    addToolCall: (messageId: string, toolCall: ToolCall) => void;
    updateToolCall: (messageId: string, toolCallId: string, updates: Partial<ToolCall>) => void;
    setThinking: (thinking: boolean) => void;
    appendThinkingText: (text: string) => void;
    clearThinkingText: () => void;
    setMessageThinking: (messageId: string, text: string) => void;
    setTokenUsage: (usage: TokenUsage) => void;
    setEstimatedCost: (cost: number) => void;
    setCurrentModel: (model: string) => void;
    setRunning: (running: boolean) => void;
    recordEvent: (ts?: number) => void;
    clearMessages: () => void;
    restoreSession: (id: string, messages: Message[], running: boolean) => void;
}

/**
 * Compute the legacy top-level mirror fields from the active session entry. Called
 * inside every setter so reads of `state.messages` etc. always reflect the active
 * tab. Pure function — caller spreads the result into the new state.
 */
function activeMirror(
    sessions: Record<string, PerSession>,
    activeSessionId: string | null,
): Pick<
    SessionsState,
    'sessionId' | 'messages' | 'isThinking' | 'thinkingText' | 'tokenUsage' | 'estimatedCost' | 'currentModel' | 'todos' | 'running' | 'lastEventAt'
> {
    const s = activeSessionId ? sessions[activeSessionId] : null;
    return {
        sessionId: activeSessionId,
        messages: s?.messages ?? [],
        isThinking: s?.isThinking ?? false,
        thinkingText: s?.thinkingText ?? '',
        tokenUsage: s?.tokenUsage ?? { input: 0, output: 0 },
        estimatedCost: s?.estimatedCost ?? 0,
        currentModel: s?.currentModel ?? '',
        todos: s?.todos ?? [],
        running: s?.running ?? false,
        lastEventAt: s?.lastEventAt ?? 0,
    };
}

/** Patch a per-session entry, ensuring the entry exists and the active mirror updates. */
function patchSession(
    state: SessionsState,
    sid: string,
    patch: (s: PerSession) => PerSession,
): Partial<SessionsState> {
    const existing = state.sessions[sid] ?? EMPTY_SESSION;
    const next = patch(existing);
    const sessions = { ...state.sessions, [sid]: next };
    return { sessions, ...activeMirror(sessions, state.activeSessionId) };
}

export const useSessionStore = create<SessionsState>((set, get) => ({
    sessions: {},
    openTabs: [],
    activeSessionId: null,
    sessionId: null,
    messages: [],
    isThinking: false,
    thinkingText: '',
    tokenUsage: { input: 0, output: 0 },
    estimatedCost: 0,
    currentModel: '',
    todos: [],
    running: false,
    lastEventAt: 0,

    ensureSession: (sid) =>
        set((state) =>
            state.sessions[sid]
                ? {}
                : { sessions: { ...state.sessions, [sid]: { ...EMPTY_SESSION } } },
        ),

    openSession: (sid) =>
        set((state) => {
            const sessions = state.sessions[sid]
                ? state.sessions
                : { ...state.sessions, [sid]: { ...EMPTY_SESSION } };
            const openTabs = state.openTabs.includes(sid)
                ? state.openTabs
                : [...state.openTabs, sid];
            return { sessions, openTabs, activeSessionId: sid, ...activeMirror(sessions, sid) };
        }),

    closeSession: (sid) =>
        set((state) => {
            const idx = state.openTabs.indexOf(sid);
            if (idx < 0) return {};
            const openTabs = state.openTabs.filter((t) => t !== sid);
            // Pick the right neighbor (or left if closing the last tab) as the new
            // active. Mirrors VS Code editor tab close behavior.
            let nextActive = state.activeSessionId;
            if (state.activeSessionId === sid) {
                nextActive = openTabs[idx] ?? openTabs[idx - 1] ?? openTabs[0] ?? null;
            }
            const sessions = { ...state.sessions };
            delete sessions[sid];
            return { sessions, openTabs, activeSessionId: nextActive, ...activeMirror(sessions, nextActive) };
        }),

    setActiveSession: (sid) =>
        set((state) => {
            if (sid === state.activeSessionId) return {};
            // Auto-open if not in tabs (defensive: caller may forget).
            const sessions = sid && !state.sessions[sid]
                ? { ...state.sessions, [sid]: { ...EMPTY_SESSION } }
                : state.sessions;
            const openTabs = sid && !state.openTabs.includes(sid)
                ? [...state.openTabs, sid]
                : state.openTabs;
            return { sessions, openTabs, activeSessionId: sid, ...activeMirror(sessions, sid) };
        }),

    setSessionTitle: (sid, title) =>
        set((state) => patchSession(state, sid, (s) => ({ ...s, title }))),

    // ── Per-session explicit mutators ────────────────────────────────────────

    addMessageTo: (sid, msg) =>
        set((state) => patchSession(state, sid, (s) => ({ ...s, messages: [...s.messages, msg] }))),

    setMessagesFor: (sid, messages) =>
        set((state) => patchSession(state, sid, (s) => ({ ...s, messages }))),

    appendChunkTo: (sid, mid, text) =>
        set((state) =>
            patchSession(state, sid, (s) => ({
                ...s,
                messages: s.messages.map((m) =>
                    m.id === mid ? { ...m, content: m.content + text } : m,
                ),
            })),
        ),

    addToolCallTo: (sid, mid, toolCall) =>
        set((state) =>
            patchSession(state, sid, (s) => {
                const messages = s.messages.map((m) => {
                    if (m.id !== mid) return m;
                    const calls = m.toolCalls ?? [];
                    // Dedupe by id: replace if a call with the same id already exists on this message.
                    const existingIdx = calls.findIndex((tc) => tc.id === toolCall.id);
                    if (existingIdx >= 0) {
                        const next = calls.slice();
                        next[existingIdx] = { ...next[existingIdx], ...toolCall };
                        return { ...m, toolCalls: next };
                    }
                    return { ...m, toolCalls: [...calls, toolCall] };
                });
                // Cross-message dedupe: drop the new call if its id already lives on a different message.
                const elsewhere = s.messages.some(
                    (other) => other.id !== mid && (other.toolCalls ?? []).some((tc) => tc.id === toolCall.id),
                );
                return { ...s, messages: elsewhere ? s.messages : messages };
            }),
        ),

    updateToolCallIn: (sid, mid, tcid, updates) =>
        set((state) =>
            patchSession(state, sid, (s) => ({
                ...s,
                messages: s.messages.map((m) =>
                    m.id === mid
                        ? {
                              ...m,
                              toolCalls: (m.toolCalls ?? []).map((tc) =>
                                  tc.id === tcid ? { ...tc, ...updates } : tc,
                              ),
                          }
                        : m,
                ),
            })),
        ),

    setThinkingFor: (sid, isThinking) =>
        set((state) => patchSession(state, sid, (s) => ({ ...s, isThinking }))),

    appendThinkingTextTo: (sid, text) =>
        set((state) =>
            patchSession(state, sid, (s) => ({ ...s, thinkingText: s.thinkingText + text })),
        ),

    clearThinkingTextFor: (sid) =>
        set((state) => patchSession(state, sid, (s) => ({ ...s, thinkingText: '' }))),

    setMessageThinkingIn: (sid, mid, text) =>
        set((state) =>
            patchSession(state, sid, (s) => ({
                ...s,
                messages: s.messages.map((m) =>
                    m.id === mid ? { ...m, thinking: (m.thinking ?? '') + text } : m,
                ),
            })),
        ),

    setTokenUsageFor: (sid, tokenUsage) =>
        set((state) => patchSession(state, sid, (s) => ({ ...s, tokenUsage }))),

    setEstimatedCostFor: (sid, estimatedCost) =>
        set((state) => patchSession(state, sid, (s) => ({ ...s, estimatedCost }))),

    setCurrentModelFor: (sid, currentModel) =>
        set((state) => patchSession(state, sid, (s) => ({ ...s, currentModel }))),

    setTodosFor: (sid, todos) =>
        set((state) => patchSession(state, sid, (s) => ({ ...s, todos }))),

    setRunningFor: (sid, running) =>
        set((state) => patchSession(state, sid, (s) => ({ ...s, running }))),

    recordEventFor: (sid, ts) =>
        set((state) =>
            patchSession(state, sid, (s) => ({ ...s, lastEventAt: ts ?? Date.now() })),
        ),

    clearMessagesFor: (sid) =>
        set((state) => patchSession(state, sid, (s) => ({ ...s, messages: [] }))),

    restoreSessionAs: (sid, messages, running, todos) =>
        set((state) => {
            const prev = state.sessions[sid] ?? EMPTY_SESSION;
            const sessions = {
                ...state.sessions,
                [sid]: {
                    ...prev,
                    messages,
                    isThinking: running,
                    thinkingText: '',
                    todos: todos ?? prev.todos ?? [],
                    running,
                    lastEventAt: Date.now(),
                },
            };
            return { sessions, ...activeMirror(sessions, state.activeSessionId) };
        }),

    // ── Legacy actions (route to active session) ─────────────────────────────

    setSessionId: (id) => {
        // Old call sites used setSessionId(null) on logout/clear and setSessionId(newId)
        // on session swap. Treat both as setActiveSession (auto-opens tab if needed).
        get().setActiveSession(id);
    },

    addMessage: (message) => {
        const sid = get().activeSessionId;
        if (sid) get().addMessageTo(sid, message);
    },

    setMessages: (messages) => {
        const sid = get().activeSessionId;
        if (sid) get().setMessagesFor(sid, messages);
    },

    appendChunk: (mid, text) => {
        const sid = get().activeSessionId;
        if (sid) get().appendChunkTo(sid, mid, text);
    },

    addToolCall: (mid, tc) => {
        const sid = get().activeSessionId;
        if (sid) get().addToolCallTo(sid, mid, tc);
    },

    updateToolCall: (mid, tcid, upd) => {
        const sid = get().activeSessionId;
        if (sid) get().updateToolCallIn(sid, mid, tcid, upd);
    },

    setThinking: (b) => {
        const sid = get().activeSessionId;
        if (sid) get().setThinkingFor(sid, b);
    },

    appendThinkingText: (text) => {
        const sid = get().activeSessionId;
        if (sid) get().appendThinkingTextTo(sid, text);
    },

    clearThinkingText: () => {
        const sid = get().activeSessionId;
        if (sid) get().clearThinkingTextFor(sid);
    },

    setMessageThinking: (mid, text) => {
        const sid = get().activeSessionId;
        if (sid) get().setMessageThinkingIn(sid, mid, text);
    },

    setTokenUsage: (u) => {
        const sid = get().activeSessionId;
        if (sid) get().setTokenUsageFor(sid, u);
    },

    setEstimatedCost: (c) => {
        const sid = get().activeSessionId;
        if (sid) get().setEstimatedCostFor(sid, c);
    },

    setCurrentModel: (model) => {
        const sid = get().activeSessionId;
        if (sid) get().setCurrentModelFor(sid, model);
    },

    setRunning: (running) => {
        const sid = get().activeSessionId;
        if (sid) get().setRunningFor(sid, running);
    },

    recordEvent: (ts) => {
        const sid = get().activeSessionId;
        if (sid) get().recordEventFor(sid, ts);
    },

    clearMessages: () => {
        const sid = get().activeSessionId;
        if (sid) get().clearMessagesFor(sid);
    },

    restoreSession: (id, messages, running) => {
        // Legacy: setActiveSession(id) → restore its content.
        get().setActiveSession(id);
        get().restoreSessionAs(id, messages, running);
    },
}));
