import { describe, it, expect } from 'vitest';
import { parseMarkdownContent } from '../markdownTable';

describe('parseMarkdownContent', () => {
    it('returns single text segment for plain text', () => {
        const segs = parseMarkdownContent('Hello world');
        expect(segs).toHaveLength(1);
        expect(segs[0].type).toBe('text');
    });

    it('parses a simple table', () => {
        const md = '| Name | Age |\n| --- | --- |\n| Alice | 30 |\n| Bob | 25 |';
        const segs = parseMarkdownContent(md);
        expect(segs).toHaveLength(1);
        expect(segs[0].type).toBe('table');
        if (segs[0].type === 'table') {
            expect(segs[0].table.headers).toEqual(['Name', 'Age']);
            expect(segs[0].table.rows).toHaveLength(2);
            expect(segs[0].table.rows[0]).toEqual(['Alice', '30']);
        }
    });

    it('splits text before and after table', () => {
        const md = 'Intro text\n\n| A | B |\n| - | - |\n| 1 | 2 |\n\nAfter text';
        const segs = parseMarkdownContent(md);
        const types = segs.map(s => s.type);
        expect(types).toContain('text');
        expect(types).toContain('table');
    });

    it('parses alignment from separator row', () => {
        const md = '| L | C | R |\n| :-- | :-: | --: |\n| a | b | c |';
        const segs = parseMarkdownContent(md);
        expect(segs[0].type).toBe('table');
        if (segs[0].type === 'table') {
            expect(segs[0].table.alignments[0]).toBe('left');
            expect(segs[0].table.alignments[1]).toBe('center');
            expect(segs[0].table.alignments[2]).toBe('right');
        }
    });

    it('handles content with no tables', () => {
        const md = '# Heading\n\nSome **bold** text\n\n```js\ncode\n```';
        const segs = parseMarkdownContent(md);
        expect(segs.every(s => s.type === 'text')).toBe(true);
    });

    it('handles multiple tables in sequence', () => {
        const t1 = '| A |\n| - |\n| 1 |';
        const t2 = '| B |\n| - |\n| 2 |';
        const segs = parseMarkdownContent(`${t1}\n\n${t2}`);
        const tables = segs.filter(s => s.type === 'table');
        expect(tables.length).toBeGreaterThanOrEqual(2);
    });

    it('returns empty array for empty string', () => {
        const segs = parseMarkdownContent('');
        expect(segs).toHaveLength(1);
        expect(segs[0].type).toBe('text');
        if (segs[0].type === 'text') {
            expect(segs[0].content).toBe('');
        }
    });
});
