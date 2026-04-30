import { describe, it, expect, beforeEach } from 'vitest';
import { saveDraft, loadDraft, clearDraft } from '../inputDraft';

describe('inputDraft', () => {
    beforeEach(() => localStorage.clear());

    it('saves and loads a draft', () => {
        saveDraft('session-1', 'Hello world');
        expect(loadDraft('session-1')).toBe('Hello world');
    });

    it('returns empty string when no draft', () => {
        expect(loadDraft('session-99')).toBe('');
    });

    it('removes draft when text is empty/whitespace', () => {
        saveDraft('session-1', 'text');
        saveDraft('session-1', '   ');
        expect(loadDraft('session-1')).toBe('');
    });

    it('clears draft explicitly', () => {
        saveDraft('session-1', 'text');
        clearDraft('session-1');
        expect(loadDraft('session-1')).toBe('');
    });

    it('isolates drafts per session', () => {
        saveDraft('s1', 'message A');
        saveDraft('s2', 'message B');
        expect(loadDraft('s1')).toBe('message A');
        expect(loadDraft('s2')).toBe('message B');
    });

    it('truncates drafts longer than MAX_DRAFT_LENGTH', () => {
        const longText = 'a'.repeat(15_000);
        saveDraft('s1', longText);
        expect(loadDraft('s1').length).toBeLessThanOrEqual(10_000);
    });

    it('no-ops gracefully when sessionId is empty', () => {
        expect(() => saveDraft('', 'text')).not.toThrow();
        expect(loadDraft('')).toBe('');
    });
});
