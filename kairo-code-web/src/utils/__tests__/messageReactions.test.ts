import { describe, it, expect, beforeEach } from 'vitest';
import { getReaction, setReaction, getAllReactions } from '../messageReactions';

beforeEach(() => {
    localStorage.clear();
});

describe('messageReactions', () => {
    it('returns null for unknown messageId', () => {
        expect(getReaction('msg-1')).toBeNull();
    });

    it('sets and retrieves a thumbs-up reaction', () => {
        setReaction('msg-1', 'up');
        const entry = getReaction('msg-1');
        expect(entry?.reaction).toBe('up');
        expect(entry?.ts).toBeGreaterThan(0);
    });

    it('sets and retrieves a thumbs-down reaction', () => {
        setReaction('msg-2', 'down');
        expect(getReaction('msg-2')?.reaction).toBe('down');
    });

    it('stores a note with the reaction', () => {
        setReaction('msg-3', 'up', 'great answer');
        expect(getReaction('msg-3')?.note).toBe('great answer');
    });

    it('removes reaction when set to null', () => {
        setReaction('msg-4', 'up');
        setReaction('msg-4', null);
        expect(getReaction('msg-4')).toBeNull();
    });

    it('toggles reaction (up → null → up)', () => {
        setReaction('msg-5', 'up');
        setReaction('msg-5', null);
        setReaction('msg-5', 'up');
        expect(getReaction('msg-5')?.reaction).toBe('up');
    });

    it('getAllReactions returns all stored reactions', () => {
        setReaction('msg-a', 'up');
        setReaction('msg-b', 'down');
        const all = getAllReactions();
        expect(all['msg-a'].reaction).toBe('up');
        expect(all['msg-b'].reaction).toBe('down');
    });

    it('overwrites previous reaction with new one', () => {
        setReaction('msg-6', 'up');
        setReaction('msg-6', 'down');
        expect(getReaction('msg-6')?.reaction).toBe('down');
    });
});
