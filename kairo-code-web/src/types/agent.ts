/**
 * Agent event and message type definitions for kairo-code-web.
 */

export type AgentEventType =
    | 'TEXT_CHUNK'
    | 'TOOL_CALL'
    | 'TOOL_RESULT'
    | 'TOOL_PROGRESS'
    | 'AGENT_DONE'
    | 'AGENT_ERROR'
    | 'AGENT_THINKING'
    | 'SESSION_RESTORED'
    | 'TODOS_UPDATED'
    | 'CONTEXT_COMPACTED'
    | 'PLAN_READY'
    | 'REVERTED'
    | 'MODE_DEMOTED'
    | 'MODE_ESCALATED'
    | 'TOOL_OUTPUT_CHUNK'
    | 'PEER_MESSAGE'
    | 'SESSION_RESUMED'
    | 'CLEAR_EXECUTION_MESSAGES';

/**
 * Mirrors {@code io.kairo.api.tool.FailureReason}. Carried on TOOL_RESULT payloads under
 * {@code resultMetadata.failureReason} when the tool ended in a non-success state. Absent
 * means legacy / generic error.
 */
export type FailureReason =
    | 'TIMEOUT'
    | 'USER_CANCELLED'
    | 'INTERRUPTED'
    | 'HANDLER_ERROR'
    | 'VALIDATION';

export interface AgentEvent {
    type: AgentEventType;
    sessionId: string;
    payload: AgentEventPayload;
    timestamp: number;
}

export type AgentEventPayload =
    | TextChunkPayload
    | ToolCallPayload
    | ToolResultPayload
    | ToolProgressPayload
    | AgentDonePayload
    | AgentErrorPayload
    | AgentThinkingPayload
    | SessionRestoredPayload
    | TodosUpdatedPayload
    | ContextCompactedPayload
    | PlanReadyPayload
    | RevertedPayload
    | ModeDemotedPayload
    | ModeEscalatedPayload
    | ToolOutputChunkPayload
    | PeerMessagePayload
    | Record<string, never>;

export interface TextChunkPayload {
    text: string;
}

export interface ToolCallPayload {
    toolCallId: string;
    toolName: string;
    input: Record<string, unknown>;
    requiresApproval: boolean;
}

export interface ToolResultPayload {
    toolCallId: string;
    result: string;
    isError: boolean;
    durationMs: number;
    /**
     * Structured tags carried with the result. Currently surfaces {@code failureReason}
     * (mirrors backend {@code FailureReason} enum) for non-success outcomes so the UI can
     * render distinct chips (timeout, cancelled, …) without parsing free text.
     */
    resultMetadata?: Record<string, unknown>;
}

/**
 * Heartbeat emitted every ~5s while a tool exceeds a threshold (default 30s) so the UI can
 * render a live waiting indicator. {@code phase} is one of EXECUTING / AWAITING_APPROVAL /
 * STREAMING; {@code elapsedMs} is the wall-clock time since the tool started.
 */
export interface ToolProgressPayload {
    toolCallId: string;
    toolName: string;
    phase: 'EXECUTING' | 'AWAITING_APPROVAL' | 'STREAMING';
    elapsedMs: number;
}

/**
 * Incremental output chunk from a streaming tool (typically bash). Appended to the
 * ToolCall's {@code partialOutput} so the UI can render a live terminal.
 */
export interface ToolOutputChunkPayload {
    toolCallId: string;
    content: string;
}

export interface AgentDonePayload {
    inputTokens: number;
    outputTokens: number;
    cost?: number;
}

export interface AgentErrorPayload {
    message: string;
    errorType?: string;
}

export interface AgentThinkingPayload {
    isThinking: boolean;
}

/**
 * Payload for SESSION_RESTORED event.
 * messages: array of Message objects restored from the backend checkpoint.
 * running: whether the session is currently executing an agent call.
 * todos: latest snapshot of {@code .kairo/todos.json}; empty when no todos exist.
 */
export interface SessionRestoredPayload {
    messages: Message[];
    running: boolean;
    todos?: Todo[];
    /** True when the backend session is in a resumable (FAILED_*) phase — restores the
     *  general-flow "Resume" affordance after a page reload. */
    resumable?: boolean;
}

/**
 * Single todo item stored by the agent's {@code todo_write} tool. Mirrors the on-disk schema
 * at {@code .kairo/todos.json}: {@code {id, content, status, priority?}}.
 */
export interface Todo {
    id: string;
    content: string;
    status: 'pending' | 'in_progress' | 'completed';
    priority?: 'high' | 'medium' | 'low';
}

/**
 * Payload for TODOS_UPDATED event, emitted after every {@code todo_write} / {@code todo_read}
 * tool result. Carries the full snapshot — the panel renders this as authoritative state.
 */
export interface TodosUpdatedPayload {
    todos: Todo[];
}

/**
 * Single item in an {@code exit_plan_mode} approval card. Mirrors the backend
 * {@code ExitPlanModeTool} schema: {@code {content, priority?}}. The card lets
 * the user toggle inclusion, edit content, and reorder priority before approval;
 * the resulting array is sent back via {@code editedArgs.items}.
 */
export interface PlanItem {
    content: string;
    priority?: 'high' | 'medium' | 'low';
}

/**
 * Payload for CONTEXT_COMPACTED event, emitted when the backend ContextCompactionHook
 * triggers a compaction injection. {@code beforeTokens} is the estimated context size
 * at trigger time, {@code maxTokens} is the configured context window, and {@code ratio}
 * is {@code beforeTokens / maxTokens} clamped to [0, 1].
 */
export interface ContextCompactedPayload {
    beforeTokens: number;
    maxTokens: number;
    ratio: number;
}

export interface Message {
    id: string;
    role: 'user' | 'assistant' | 'error';
    content: string;
    toolCalls: ToolCall[];
    timestamp: number;
    streaming?: boolean;
    imageData?: string;        // base64 encoded image data
    imageMediaType?: string;   // e.g. "image/png"
    /** When set, this message renders as a live expert-step card (qoder-style inline agent
     *  card) bound to expertTeamStore, instead of a plain text bubble. */
    kind?: 'expertStep' | 'compaction';
    stepRef?: { teamId: string; stepId: string };
    compactionMeta?: { beforeTokens: number; maxTokens: number; summary?: string };
    /** Reasoning_content captured during AGENT_THINKING events. Pinned onto the
     *  message so users can expand it in the chat after the response completes,
     *  mirroring Claude Code's collapsible 思考过程 box. */
    thinking?: string;
}

export interface SubagentEvent {
    childEventType: 'TOOL_CALL' | 'TOOL_RESULT';
    childToolName?: string;
    childIsError?: boolean;
    timestamp: number;
}

export interface ToolCall {
    id: string;
    toolName: string;
    input: Record<string, unknown>;
    result?: string;
    status: 'pending' | 'approved' | 'rejected' | 'done' | 'error';
    requiresApproval: boolean;
    durationMs?: number;
    isError?: boolean;
    /** Carried from TOOL_RESULT.resultMetadata.failureReason; absent for legacy/success. */
    failureReason?: FailureReason;
    /** Latest TOOL_PROGRESS heartbeat phase, if the tool is still in flight. */
    progressPhase?: 'EXECUTING' | 'AWAITING_APPROVAL' | 'STREAMING';
    /** Wall-clock ms since tool started, updated on each TOOL_PROGRESS tick. */
    progressElapsedMs?: number;
    /** Accumulated streaming output chunks (bash stdout/stderr). */
    partialOutput?: string;
    /** Epoch ms when the TOOL_CALL event was received. */
    createdAt?: number;
    /** Full metadata from TOOL_RESULT (task.outcome, task.files_changed, etc.) */
    resultMetadata?: Record<string, unknown>;
    /** Child agent events forwarded via SUBAGENT_EVENT (task tool only). */
    subagentEvents?: SubagentEvent[];
}

export interface ServerConfig {
    provider: string;
    model: string;
    /**
     * @deprecated workingDir moved to Workspace. Kept temporarily for legacy callers
     *  that have not migrated; new code should read from useWorkspaceStore.
     */
    workingDir?: string;
    baseUrl?: string;
    apiKeySet: boolean;
    defaultModel: string;
    availableModels: string[];
    thinkingBudget?: number | null;
}

export interface SessionInfo {
    sessionId: string;
    model: string;
    createdAt: number;
    workingDir?: string;
    running?: boolean;
    workspaceId?: string;
    isGit?: boolean;
}

/**
 * Payload for PLAN_READY event — emitted when the agent has finished planning and
 * is awaiting user confirmation to proceed with execution.
 *
 * In experts mode the backend also stamps {@code teamId} (via resultMetadata) so
 * the always-on Canvas pane can auto-attach to the running expert team.
 */
export interface PlanReadyPayload {
    planSummary?: string;
    teamId?: string;
    goal?: string;
    steps?: Array<{
        stepId: string;
        roleId: string;
        roleName: string;
        instruction: string;
        dependsOn: string[];
        stepIndex: number;
    }>;
    mode?: string;
    planId?: string;
    totalSteps?: number;
}

/**
 * Payload for REVERTED event — emitted after a successful git revert.
 */
export interface RevertedPayload {
    message?: string;
}

/**
 * Payload for MODE_DEMOTED event — emitted when the experts-mode triage gate
 * decides a message is too short/simple for expert team fanout. The UI should
 * render this as an info banner (not an error).
 */
export interface ModeDemotedPayload {
    reason: string;
}

export interface ModeEscalatedPayload {
    reason: string;
}

/**
 * Payload for PEER_MESSAGE event — emitted by TeamSessionPayload (M-Team / #60) when
 * a peer agent in the same team has sent a message via the in-process MessageBus.
 * Rendered as a system-styled bubble in the chat.
 */
export interface PeerMessagePayload {
    fromSessionId: string;
    content: string;
    messageId: string;
    /** Originating expert step — present for step-level events; absent for team-level ones. */
    stepId?: string;
    teamId?: string;
}

export interface TokenUsage {
    input: number;
    output: number;
}

export interface FileEntry {
    name: string;
    path: string;
    isDir: boolean;
    size: number;
}

export interface FileContentResponse {
    path: string;
    content: string;
    language: string;
}

export interface SearchMatch {
    file: string;
    line: number;
    preview: string;
}

export interface SearchResponse {
    query: string;
    matches: SearchMatch[];
    truncated: boolean;
}

/**
 * Plan lifecycle phase for the confirm/revert UI.
 * - idle: no plan in progress
 * - PLAN_PENDING: plan is ready, awaiting user confirmation
 * - EXECUTING: build in progress
 * - FAILED_EXECUTION: build failed
 * - COMPLETED: build succeeded
 * - REVERTED: user reverted changes
 */
export type PlanPhase = 'idle' | 'PLAN_PENDING' | 'EXECUTING' | 'FAILED_EXECUTION' | 'COMPLETED' | 'REVERTED';

/** WebSocket connection status for the UI indicator. */
export type ConnectionStatus = 'connected' | 'connecting' | 'disconnected' | 'error';
