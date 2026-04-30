import type { Message } from '@/types/agent';
import { fuzzyMatch } from './fuzzyMatch';

export interface MessageSearchResult {
    messageIndex: number;
    score: number;
}

export function searchMessages(messages: Message[], query: string): MessageSearchResult[] {
    if (!query.trim()) return [];

    const results: MessageSearchResult[] = [];

    messages.forEach((msg, idx) => {
        let best = -1;

        // Search in message content
        if (msg.content) {
            const s = fuzzyMatch(msg.content, query);
            if (s > best) best = s;
        }

        // Search in tool call names and inputs
        for (const tc of msg.toolCalls ?? []) {
            const s = Math.max(
                fuzzyMatch(tc.toolName ?? '', query),
                tc.input ? fuzzyMatch(JSON.stringify(tc.input), query) : -1,
            );
            if (s > best) best = s;
        }

        if (best >= 0) {
            results.push({ messageIndex: idx, score: best });
        }
    });

    // Sort by score descending, preserving original order for equal scores
    return results.sort((a, b) =>
        b.score !== a.score ? b.score - a.score : a.messageIndex - b.messageIndex,
    );
}
