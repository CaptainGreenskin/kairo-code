import { describe, it, expect, beforeEach } from 'vitest';
import { loadHistory, pushHistory, clearHistory } from '../inputHistory';

describe('inputHistory', () => {
    beforeEach(() => localStorage.clear());

    it('returns empty array initially', () => {
        expect(loadHistory('s1')).toEqual([]);
    });

    it('pushes and loads history', () => {
        pushHistory('s1', 'hello');
        expect(loadHistory('s1')).toEqual(['hello']);
    });

    it('prepends (newest first)', () => {
        pushHistory('s1', 'first');
        pushHistory('s1', 'second');
        expect(loadHistory('s1')[0]).toBe('second');
    });

    it('deduplicates — moves existing entry to front', () => {
        pushHistory('s1', 'a');
        pushHistory('s1', 'b');
        pushHistory('s1', 'a');
        const h = loadHistory('s1');
        expect(h[0]).toBe('a');
        expect(h.filter(x => x === 'a').length).toBe(1);
    });

    it('ignores empty text', () => {
        pushHistory('s1', '');
        expect(loadHistory('s1')).toEqual([]);
    });

    it('ignores whitespace-only text', () => {
        pushHistory('s1', '  ');
        expect(loadHistory('s1')).toEqual([]);
    });

    it('trims text before storing', () => {
        pushHistory('s1', '  hello  ');
        expect(loadHistory('s1')[0]).toBe('hello');
    });

    it('clears history', () => {
        pushHistory('s1', 'x');
        clearHistory('s1');
        expect(loadHistory('s1')).toEqual([]);
    });

    it('history is isolated per session', () => {
        pushHistory('s1', 'session-one');
        pushHistory('s2', 'session-two');
        expect(loadHistory('s1')).toEqual(['session-one']);
        expect(loadHistory('s2')).toEqual(['session-two']);
    });
});
