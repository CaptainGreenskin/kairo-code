import { describe, it, expect, beforeEach } from 'vitest';
import { getSessionName, setSessionName, removeSessionName } from '../sessionNames';

describe('sessionNames', () => {
    beforeEach(() => localStorage.clear());

    it('returns null for unknown session', () => {
        expect(getSessionName('s1')).toBeNull();
    });

    it('sets and gets a name', () => {
        setSessionName('s1', 'My Session');
        expect(getSessionName('s1')).toBe('My Session');
    });

    it('trims whitespace on save', () => {
        setSessionName('s1', '  hello  ');
        expect(getSessionName('s1')).toBe('hello');
    });

    it('removes name when empty string set', () => {
        setSessionName('s1', 'name');
        setSessionName('s1', '');
        expect(getSessionName('s1')).toBeNull();
    });

    it('removes name when whitespace-only string set', () => {
        setSessionName('s1', 'name');
        setSessionName('s1', '   ');
        expect(getSessionName('s1')).toBeNull();
    });

    it('isolates names per session', () => {
        setSessionName('s1', 'alpha');
        expect(getSessionName('s2')).toBeNull();
    });

    it('overwrites existing name', () => {
        setSessionName('s1', 'old');
        setSessionName('s1', 'new');
        expect(getSessionName('s1')).toBe('new');
    });

    it('removeSessionName deletes the entry', () => {
        setSessionName('s1', 'name');
        removeSessionName('s1');
        expect(getSessionName('s1')).toBeNull();
    });

    it('handles malformed localStorage gracefully', () => {
        localStorage.setItem('kairo-session-names', 'not-json');
        expect(() => getSessionName('s1')).not.toThrow();
        expect(getSessionName('s1')).toBeNull();
    });
});
