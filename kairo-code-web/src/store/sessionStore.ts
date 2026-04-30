import { create } from 'zustand';
import type { Message, ToolCall, TokenUsage } from '@/types/agent';

interface SessionState {
    sessionId: string | null;
    messages: Message[];
    isThinking: boolean;
    tokenUsage: TokenUsage;
    estimatedCost: number;
    currentModel: string;

    // Actions
    setSessionId: (id: string) => void;
    addMessage: (message: Message) => void;
    appendChunk: (messageId: string, text: string) => void;
    addToolCall: (messageId: string, toolCall: ToolCall) => void;
    updateToolCall: (messageId: string, toolCallId: string, updates: Partial<ToolCall>) => void;
    setThinking: (thinking: boolean) => void;
    setTokenUsage: (usage: TokenUsage) => void;
    setEstimatedCost: (cost: number) => void;
    setCurrentModel: (model: string) => void;
    clearMessages: () => void;
}

function generateId(): string {
    return crypto.randomUUID();
}

export const useSessionStore = create<SessionState>((set) => ({
    sessionId: null,
    messages: [],
    isThinking: false,
    tokenUsage: { input: 0, output: 0 },
    estimatedCost: 0,
    currentModel: '',

    setSessionId: (id) => set({ sessionId: id }),

    addMessage: (message) =>
        set((state) => ({ messages: [...state.messages, message] })),

    appendChunk: (messageId, text) =>
        set((state) => ({
            messages: state.messages.map((m) =>
                m.id === messageId ? { ...m, content: m.content + text } : m,
            ),
        })),

    addToolCall: (messageId, toolCall) =>
        set((state) => ({
            messages: state.messages.map((m) =>
                m.id === messageId
                    ? { ...m, toolCalls: [...m.toolCalls, toolCall] }
                    : m,
            ),
        })),

    updateToolCall: (messageId, toolCallId, updates) =>
        set((state) => ({
            messages: state.messages.map((m) =>
                m.id === messageId
                    ? {
                          ...m,
                          toolCalls: m.toolCalls.map((tc) =>
                              tc.id === toolCallId ? { ...tc, ...updates } : tc,
                          ),
                      }
                    : m,
            ),
        })),

    setThinking: (thinking) => set({ isThinking: thinking }),

    setTokenUsage: (usage) => set({ tokenUsage: usage }),

    setEstimatedCost: (cost) => set({ estimatedCost: cost }),

    setCurrentModel: (model) => set({ currentModel: model }),

    clearMessages: () => set({ messages: [] }),
}));
