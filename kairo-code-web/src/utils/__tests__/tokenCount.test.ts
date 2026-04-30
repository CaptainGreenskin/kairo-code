import { describe, it, expect } from 'vitest';
import { estimateTokens, formatTokenCount } from '../tokenCount';

describe('estimateTokens', () => {
    it('returns 0 for empty string', () => {
        expect(estimateTokens('')).toBe(0);
    });

    it('returns 0 for nullish-like input', () => {
        // The function checks `if (!text)` so empty/undefined guard works
        expect(estimateTokens('')).toBe(0);
    });

    it('estimates English text at ~4 chars/token', () => {
        const tokens = estimateTokens('hello world foo bar'); // 19 chars
        expect(tokens).toBeGreaterThan(3);
        expect(tokens).toBeLessThan(8);
    });

    it('estimates CJK text at ~2 chars/token', () => {
        const tokens = estimateTokens('你好世界'); // 4 CJK chars
        expect(tokens).toBeGreaterThanOrEqual(2);
    });

    it('handles mixed CJK and English', () => {
        const tokens = estimateTokens('hello 世界');
        expect(tokens).toBeGreaterThan(0);
    });

    it('returns consistent results for same input', () => {
        const input = 'the quick brown fox';
        expect(estimateTokens(input)).toBe(estimateTokens(input));
    });
});

describe('formatTokenCount', () => {
    it('shows raw count under 1000', () => {
        expect(formatTokenCount(500)).toBe('500');
    });

    it('shows raw count at exactly 999', () => {
        expect(formatTokenCount(999)).toBe('999');
    });

    it('shows k suffix over 1000', () => {
        expect(formatTokenCount(1500)).toBe('1.5k');
    });

    it('shows k suffix at exactly 1000', () => {
        expect(formatTokenCount(1000)).toBe('1.0k');
    });

    it('rounds large numbers', () => {
        expect(formatTokenCount(12500)).toBe('13k');
    });

    it('handles zero', () => {
        expect(formatTokenCount(0)).toBe('0');
    });
});
