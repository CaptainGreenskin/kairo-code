import { describe, it, expect } from 'vitest';
import { countLines, isCollapsible, getPreviewContent, COLLAPSE_LINE_THRESHOLD, COLLAPSE_PREVIEW_LINES } from '../messageCollapse';

describe('messageCollapse', () => {
    it('counts lines correctly', () => {
        expect(countLines('a\nb\nc')).toBe(3);
    });

    it('counts single line', () => {
        expect(countLines('hello')).toBe(1);
    });

    it('returns 0 for empty string', () => {
        expect(countLines('')).toBe(0);
    });

    it('isCollapsible returns false for short content', () => {
        const short = Array(10).fill('line').join('\n');
        expect(isCollapsible(short)).toBe(false);
    });

    it('isCollapsible returns true for long content', () => {
        const long = Array(COLLAPSE_LINE_THRESHOLD + 5).fill('line').join('\n');
        expect(isCollapsible(long)).toBe(true);
    });

    it('isCollapsible returns false at exact threshold', () => {
        const exact = Array(COLLAPSE_LINE_THRESHOLD).fill('line').join('\n');
        expect(isCollapsible(exact)).toBe(false);
    });

    it('getPreviewContent returns first N lines', () => {
        const lines = Array.from({ length: 60 }, (_, i) => `line ${i + 1}`);
        const content = lines.join('\n');
        const preview = getPreviewContent(content);
        const previewLines = preview.split('\n');
        expect(previewLines).toHaveLength(COLLAPSE_PREVIEW_LINES);
        expect(previewLines[0]).toBe('line 1');
        expect(previewLines[COLLAPSE_PREVIEW_LINES - 1]).toBe(`line ${COLLAPSE_PREVIEW_LINES}`);
    });

    it('getPreviewContent returns full content when shorter than preview limit', () => {
        const short = 'line1\nline2\nline3';
        expect(getPreviewContent(short)).toBe(short);
    });
});
