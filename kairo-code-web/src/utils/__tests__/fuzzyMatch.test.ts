import { describe, it, expect } from 'vitest';
import { fuzzyMatch } from '../fuzzyMatch';

describe('fuzzyMatch', () => {
    it('returns 0 for empty query', () => {
        expect(fuzzyMatch('hello', '')).toBe(0);
    });

    it('returns high score for exact substring match', () => {
        expect(fuzzyMatch('Export as Markdown', 'export')).toBeGreaterThan(0);
    });

    it('matches fuzzy char sequence', () => {
        expect(fuzzyMatch('New Session', 'nses')).toBeGreaterThan(0);
    });

    it('returns -1 when no match', () => {
        expect(fuzzyMatch('Export', 'xyz')).toBe(-1);
    });

    it('exact match scores higher than fuzzy match', () => {
        const exactScore = fuzzyMatch('export', 'exp');
        const fuzzyScore = fuzzyMatch('extended port', 'exp');
        expect(exactScore).toBeGreaterThan(fuzzyScore);
    });

    it('substring match scores higher than scattered fuzzy', () => {
        const subScore = fuzzyMatch('New Session', 'session');
        const fuzzyScore = fuzzyMatch('snakey exercise ninja', 'session');
        // Exact substring always wins
        expect(subScore).toBeGreaterThan(fuzzyScore);
    });
});
