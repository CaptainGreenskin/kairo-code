import { describe, it, expect, beforeEach } from 'vitest';
import { getSessionTags, addSessionTag, removeSessionTag, getAllTags } from '../sessionTags';

describe('sessionTags', () => {
    beforeEach(() => localStorage.clear());

    it('returns empty array for unknown session', () => {
        expect(getSessionTags('abc')).toEqual([]);
    });

    it('adds a tag', () => {
        addSessionTag('s1', 'frontend');
        expect(getSessionTags('s1')).toContain('frontend');
    });

    it('trims and lowercases tags', () => {
        addSessionTag('s1', '  FrontEnd  ');
        expect(getSessionTags('s1')).toContain('frontend');
    });

    it('deduplicates tags', () => {
        addSessionTag('s1', 'bug');
        addSessionTag('s1', 'bug');
        expect(getSessionTags('s1').length).toBe(1);
    });

    it('deduplicates case-insensitively', () => {
        addSessionTag('s1', 'Bug');
        addSessionTag('s1', 'bug');
        expect(getSessionTags('s1').length).toBe(1);
    });

    it('removes a tag', () => {
        addSessionTag('s1', 'backend');
        removeSessionTag('s1', 'backend');
        expect(getSessionTags('s1')).not.toContain('backend');
    });

    it('removing a non-existent tag does not throw', () => {
        expect(() => removeSessionTag('s1', 'nonexistent')).not.toThrow();
        expect(getSessionTags('s1')).toEqual([]);
    });

    it('tags are isolated per session', () => {
        addSessionTag('s1', 'frontend');
        addSessionTag('s2', 'backend');
        expect(getSessionTags('s1')).not.toContain('backend');
        expect(getSessionTags('s2')).not.toContain('frontend');
    });

    it('getAllTags returns deduplicated sorted tags', () => {
        addSessionTag('s1', 'b');
        addSessionTag('s2', 'a');
        addSessionTag('s1', 'a');
        expect(getAllTags()).toEqual(['a', 'b']);
    });

    it('getAllTags returns empty array when no tags exist', () => {
        expect(getAllTags()).toEqual([]);
    });
});
