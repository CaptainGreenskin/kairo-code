import { create } from 'zustand';
import type { Message } from '@/types/agent';

interface QueueState {
    messages: Message[];
    enqueue: (msg: Message) => void;
    dequeue: () => Message | undefined;
    clear: () => void;
    count: number;
}

export const useQueueStore = create<QueueState>()((set, get) => ({
    messages: [],
    count: 0,
    enqueue: (msg) => set((s) => ({ messages: [...s.messages, msg], count: s.count + 1 })),
    dequeue: () => {
        const { messages } = get();
        if (messages.length === 0) return undefined;
        const [first, ...rest] = messages;
        set({ messages: rest, count: rest.length });
        return first;
    },
    clear: () => set({ messages: [], count: 0 }),
}));
