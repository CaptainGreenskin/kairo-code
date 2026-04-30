/**
 * Agent event and message type definitions for kairo-code-web.
 */

export type AgentEventType =
    | 'TEXT_CHUNK'
    | 'TOOL_CALL'
    | 'TOOL_RESULT'
    | 'AGENT_DONE'
    | 'AGENT_ERROR'
    | 'AGENT_THINKING';

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
    | AgentThinkingPayload;

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

export interface Message {
    id: string;
    role: 'user' | 'assistant';
    content: string;
    toolCalls: ToolCall[];
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
}

export interface ServerConfig {
    defaultModel: string;
    availableModels: string[];
}

export interface SessionInfo {
    sessionId: string;
    model: string;
    createdAt: number;
}

export interface TokenUsage {
    input: number;
    output: number;
}
