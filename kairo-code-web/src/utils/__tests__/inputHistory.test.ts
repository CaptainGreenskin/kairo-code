import { describe, it, expect, beforeEach } from 'vitest';
import { getHistory, pushHistory, clearHistory } from '../inputHistory';

describe('inputHistory', () => {
    beforeEach(() => localStorage.clear());

    it('returns empty array for new session', () => {
        expect(getHistory('s1')).toHaveLength(0);
    });

    it('pushes entry to history', () => {
        pushHistory('s1', 'hello');
        expect(getHistory('s1')).toEqual(['hello']);
    });

    it('does not push empty string', () => {
        pushHistory('s1', '');
        pushHistory('s1', '   ');
        expect(getHistory('s1')).toHaveLength(0);
    });

    it('does not duplicate consecutive identical entries', () => {
        pushHistory('s1', 'hello');
        pushHistory('s1', 'hello');
        expect(getHistory('s1')).toHaveLength(1);
    });

    it('allows non-consecutive duplicates', () => {
        pushHistory('s1', 'hello');
        pushHistory('s1', 'world');
        pushHistory('s1', 'hello');
        expect(getHistory('s1')).toHaveLength(3);
    });

    it('trims to MAX_HISTORY entries', () => {
        for (let i = 0; i < 110; i++) {
            pushHistory('s1', `message ${i}`);
        }
        expect(getHistory('s1').length).toBeLessThanOrEqual(100);
    });

    it('isolates history per session', () => {
        pushHistory('s1', 'msg');
        expect(getHistory('s2')).toHaveLength(0);
    });

    it('clearHistory removes all entries', () => {
        pushHistory('s1', 'hello');
        clearHistory('s1');
        expect(getHistory('s1')).toHaveLength(0);
    });
});
