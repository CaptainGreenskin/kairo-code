import { describe, it, expect } from 'vitest';
import { getContextWindow, getUsageRatio, getUsageColorClass, formatTokens } from '../tokenBudget';

describe('tokenBudget', () => {
    it('returns default window for unknown model', () => {
        expect(getContextWindow('unknown-model')).toBe(128_000);
    });

    it('matches known model by substring', () => {
        expect(getContextWindow('gpt-4o-2024-11')).toBe(128_000);
        expect(getContextWindow('claude-3-5-sonnet-20241022')).toBe(200_000);
    });

    it('getUsageRatio clamps to 0-1', () => {
        expect(getUsageRatio(0, 'gpt-4o')).toBe(0);
        expect(getUsageRatio(128_000, 'gpt-4o')).toBe(1);
        expect(getUsageRatio(999_999, 'gpt-4o')).toBe(1);
    });

    it('getUsageRatio returns correct proportion', () => {
        const ratio = getUsageRatio(64_000, 'gpt-4o');
        expect(ratio).toBeCloseTo(0.5, 2);
    });

    it('getUsageColorClass returns green below 50%', () => {
        expect(getUsageColorClass(0.3)).toBe('green');
    });

    it('getUsageColorClass returns yellow between 50-80%', () => {
        expect(getUsageColorClass(0.6)).toBe('yellow');
    });

    it('getUsageColorClass returns red above 80%', () => {
        expect(getUsageColorClass(0.85)).toBe('red');
    });

    it('formatTokens formats large numbers with K', () => {
        expect(formatTokens(1500)).toBe('1.5K');
        expect(formatTokens(128000)).toBe('128.0K');
    });

    it('formatTokens returns plain number for small values', () => {
        expect(formatTokens(500)).toBe('500');
    });
});
