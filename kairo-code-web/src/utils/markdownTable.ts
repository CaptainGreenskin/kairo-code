export interface MarkdownTable {
    headers: string[];
    rows: string[][];
    alignments: Array<'left' | 'center' | 'right' | 'none'>;
}

export type ContentSegment =
    | { type: 'text'; content: string }
    | { type: 'table'; table: MarkdownTable };

function parseCells(line: string): string[] {
    return line
        .replace(/^\||\|$/g, '')
        .split('|')
        .map(cell => cell.trim());
}

function parseAlignment(sep: string): 'left' | 'center' | 'right' | 'none' {
    const s = sep.trim();
    if (s.startsWith(':') && s.endsWith(':')) return 'center';
    if (s.endsWith(':')) return 'right';
    if (s.startsWith(':')) return 'left';
    return 'none';
}

function isSeparatorRow(line: string): boolean {
    return /^\|?[\s|:-]+\|?$/.test(line) && line.includes('-');
}

/**
 * Splits markdown content into text and table segments.
 * Tables are identified by: header row | separator row (|---|) | data rows.
 */
export function parseMarkdownContent(content: string): ContentSegment[] {
    const lines = content.split('\n');
    const segments: ContentSegment[] = [];
    let textBuffer: string[] = [];
    let i = 0;

    while (i < lines.length) {
        const line = lines[i];

        // Check if this line is a table header (contains |)
        if (line.trim().startsWith('|') || (line.includes('|') && line.trim().length > 2)) {
            // Look ahead for separator row
            const nextLine = lines[i + 1] ?? '';
            if (isSeparatorRow(nextLine)) {
                // Found a table — flush text buffer
                if (textBuffer.length > 0) {
                    segments.push({ type: 'text', content: textBuffer.join('\n') });
                    textBuffer = [];
                }

                // Parse header
                const headers = parseCells(line);
                const alignments = parseCells(nextLine).map(parseAlignment);
                const rows: string[][] = [];

                i += 2; // skip header + separator
                while (i < lines.length && lines[i].trim().startsWith('|')) {
                    rows.push(parseCells(lines[i]));
                    i++;
                }

                segments.push({
                    type: 'table',
                    table: { headers, rows, alignments },
                });
                continue;
            }
        }

        textBuffer.push(line);
        i++;
    }

    if (textBuffer.length > 0) {
        segments.push({ type: 'text', content: textBuffer.join('\n') });
    }

    return segments;
}
