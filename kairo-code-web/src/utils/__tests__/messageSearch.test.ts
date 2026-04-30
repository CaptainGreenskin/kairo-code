import { describe, it, expect } from 'vitest';
import { searchMessages } from '../messageSearch';
import type { Message } from '@/types/agent';

const msg = (content: string, role: 'user' | 'assistant' = 'user'): Message => ({
    id: content.slice(0, 8),
    role,
    content,
    toolCalls: [],
    timestamp: Date.now(),
});

describe('searchMessages', () => {
    it('returns empty for empty query', () => {
        expect(searchMessages([msg('hello')], '')).toHaveLength(0);
    });

    it('returns empty for whitespace query', () => {
        expect(searchMessages([msg('hello')], '   ')).toHaveLength(0);
    });

    it('finds exact substring match', () => {
        const results = searchMessages([msg('hello world'), msg('foo bar')], 'world');
        expect(results).toHaveLength(1);
        expect(results[0].messageIndex).toBe(0);
    });

    it('finds fuzzy match', () => {
        const results = searchMessages([msg('New Session created')], 'nses');
        expect(results).toHaveLength(1);
    });

    it('searches across multiple messages', () => {
        const msgs = [msg('first match here'), msg('nothing here'), msg('another match')];
        const results = searchMessages(msgs, 'match');
        expect(results).toHaveLength(2);
    });

    it('returns empty when no match', () => {
        expect(searchMessages([msg('hello')], 'xyz')).toHaveLength(0);
    });

    it('searches tool call names', () => {
        const m: Message = {
            id: 'tc1',
            role: 'assistant',
            content: '',
            toolCalls: [{ id: 't1', toolName: 'read_file', status: 'done', requiresApproval: false, input: {} }],
            timestamp: Date.now(),
        };
        const results = searchMessages([m], 'read_file');
        expect(results).toHaveLength(1);
    });
});
