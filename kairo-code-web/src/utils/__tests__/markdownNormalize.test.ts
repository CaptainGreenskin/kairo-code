import { describe, it, expect } from 'vitest';
import { normalizeInlineNumberedLists, normalizeAssistantMarkdown } from '../markdownNormalize';

describe('normalizeInlineNumberedLists', () => {
    it('breaks an inline run of sequential numbers into list items', () => {
        const input = 'P1：  3. **Foo** — desc 4. **Bar** — desc 5. **Baz** — desc';
        const output = normalizeInlineNumberedLists(input);
        expect(output).toContain('\n\n3. **Foo**');
        expect(output).toContain('\n\n4. **Bar**');
        expect(output).toContain('\n\n5. **Baz**');
    });

    it('leaves a single inline number alone (Section 1. Title)', () => {
        const input = 'See Section 1. Introduction for details.';
        expect(normalizeInlineNumberedLists(input)).toBe(input);
    });

    it('leaves non-sequential numbers alone (1.5 vs 2.0)', () => {
        const input = 'Compare 1.5 with 2.0 results.';
        expect(normalizeInlineNumberedLists(input)).toBe(input);
    });

    it('leaves already-formatted list rows alone', () => {
        const input = '1. first\n2. second\n3. third';
        expect(normalizeInlineNumberedLists(input)).toBe(input);
    });

    it('does not touch fenced code blocks', () => {
        const input = '```\nfoo 3. bar 4. baz 5. qux\n```';
        expect(normalizeInlineNumberedLists(input)).toBe(input);
    });

    it('does not touch inline code spans', () => {
        const input = 'try `3. one 4. two 5. three` and notice';
        expect(normalizeInlineNumberedLists(input)).toBe(input);
    });
});

describe('normalizeAssistantMarkdown', () => {
    it('returns empty string unchanged', () => {
        expect(normalizeAssistantMarkdown('')).toBe('');
    });

    it('runs multiple normalizers in sequence', () => {
        const input = '**P1**：  3. Foo — desc 4. Bar — desc';
        const output = normalizeAssistantMarkdown(input);
        expect(output).toContain('3. Foo');
        expect(output).toContain('4. Bar');
    });
});
