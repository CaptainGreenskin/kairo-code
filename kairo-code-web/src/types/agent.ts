/**
 * Agent event and message type definitions for kairo-code-web.
 */

export type AgentEventType =
    | 'TEXT_CHUNK'
    | 'TOOL_CALL'
    | 'TOOL_RESULT'
    | 'AGENT_DONE'
    | 'AGENT_ERROR'
    | 'AGENT_THINKING'
    | 'SESSION_RESTORED';

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
    | AgentDonePayload
    | AgentErrorPayload
    | AgentThinkingPayload
    | SessionRestoredPayload;

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
}

export interface AgentDonePayload {
    inputTokens: number;
    outputTokens: number;
}

export interface AgentErrorPayload {
    message: string;
}

export interface AgentThinkingPayload {
    isThinking: boolean;
}

/**
 * Payload for SESSION_RESTORED event.
 * messages: array of Message objects restored from the backend checkpoint.
 * running: whether the session is currently executing an agent call.
 */
export interface SessionRestoredPayload {
    messages: Message[];
    running: boolean;
}

export interface Message {
    id: string;
    role: 'user' | 'assistant' | 'error';
    content: string;
    toolCalls: ToolCall[];
    timestamp: number;
    streaming?: boolean;
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
}

export interface ServerConfig {
    provider: string;
    model: string;
    workingDir: string;
    baseUrl?: string;
    apiKeySet: boolean;
    defaultModel: string;
    availableModels: string[];
}

export interface SessionInfo {
    sessionId: string;
    model: string;
    createdAt: number;
    workingDir?: string;
    running?: boolean;
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

/** WebSocket connection status for the UI indicator. */
export type ConnectionStatus = 'connected' | 'connecting' | 'disconnected' | 'error';
