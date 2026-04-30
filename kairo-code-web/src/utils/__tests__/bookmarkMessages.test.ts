import { describe, it, expect, beforeEach } from 'vitest';
import { getBookmarks, isBookmarked, toggleBookmark, clearBookmarks } from '../bookmarkMessages';

describe('bookmarkMessages', () => {
    beforeEach(() => localStorage.clear());

    it('returns empty for unknown session', () => {
        expect(getBookmarks('session-x')).toHaveLength(0);
    });

    it('toggles bookmark on', () => {
        const added = toggleBookmark('s1', 'msg-1');
        expect(added).toBe(true);
        expect(isBookmarked('s1', 'msg-1')).toBe(true);
    });

    it('toggles bookmark off', () => {
        toggleBookmark('s1', 'msg-1');
        const added = toggleBookmark('s1', 'msg-1');
        expect(added).toBe(false);
        expect(isBookmarked('s1', 'msg-1')).toBe(false);
    });

    it('stores multiple bookmarks', () => {
        toggleBookmark('s1', 'msg-1');
        toggleBookmark('s1', 'msg-2');
        expect(getBookmarks('s1')).toHaveLength(2);
    });

    it('isolates bookmarks per session', () => {
        toggleBookmark('s1', 'msg-1');
        expect(isBookmarked('s2', 'msg-1')).toBe(false);
    });

    it('clearBookmarks removes all', () => {
        toggleBookmark('s1', 'msg-1');
        clearBookmarks('s1');
        expect(getBookmarks('s1')).toHaveLength(0);
    });

    it('does not duplicate bookmark', () => {
        toggleBookmark('s1', 'msg-1');
        toggleBookmark('s1', 'msg-1');
        toggleBookmark('s1', 'msg-1'); // toggle back on
        expect(getBookmarks('s1')).toHaveLength(1);
    });
});
