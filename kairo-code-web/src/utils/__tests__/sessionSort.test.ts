import { describe, it, expect } from 'vitest';
import { sortSessions } from '../sessionSort';

const sessions = [
    { id: 's1', name: 'Beta', createdAt: 1000 },
    { id: 's2', name: 'Alpha', createdAt: 3000 },
    { id: 's3', name: 'Gamma', createdAt: 2000 },
];

describe('sortSessions', () => {
    it('date-desc: newest first', () => {
        const sorted = sortSessions(sessions, 'date-desc');
        expect(sorted[0].id).toBe('s2');
        expect(sorted[2].id).toBe('s1');
    });

    it('date-asc: oldest first', () => {
        const sorted = sortSessions(sessions, 'date-asc');
        expect(sorted[0].id).toBe('s1');
        expect(sorted[2].id).toBe('s2');
    });

    it('name-asc: A to Z', () => {
        const sorted = sortSessions(sessions, 'name-asc');
        expect(sorted[0].name).toBe('Alpha');
        expect(sorted[2].name).toBe('Gamma');
    });

    it('name-desc: Z to A', () => {
        const sorted = sortSessions(sessions, 'name-desc');
        expect(sorted[0].name).toBe('Gamma');
        expect(sorted[2].name).toBe('Alpha');
    });

    it('does not mutate original array', () => {
        const original = [...sessions];
        sortSessions(sessions, 'name-asc');
        expect(sessions).toEqual(original);
    });

    it('handles missing createdAt gracefully', () => {
        const noDate = [{ id: 'a' }, { id: 'b', createdAt: 100 }];
        const sorted = sortSessions(noDate, 'date-desc');
        expect(sorted[0].id).toBe('b');
    });

    it('handles missing name gracefully (falls back to id)', () => {
        const noName = [{ id: 'z-session', createdAt: 0 }, { id: 'a-session', createdAt: 0 }];
        const sorted = sortSessions(noName, 'name-asc');
        expect(sorted[0].id).toBe('a-session');
    });

    it('empty array returns empty', () => {
        expect(sortSessions([], 'date-desc')).toHaveLength(0);
    });
});
