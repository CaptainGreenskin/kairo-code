export const COLLAPSE_LINE_THRESHOLD = 40;
export const COLLAPSE_PREVIEW_LINES = 30;

/**
 * Counts the number of lines in a string.
 */
export function countLines(text: string): number {
    if (!text) return 0;
    return text.split('\n').length;
}

/**
 * Returns whether a message should be collapsible based on its content.
 */
export function isCollapsible(content: string): boolean {
    return countLines(content) > COLLAPSE_LINE_THRESHOLD;
}

/**
 * Returns the preview content (first N lines) for a collapsible message.
 */
export function getPreviewContent(content: string): string {
    const lines = content.split('\n');
    if (lines.length <= COLLAPSE_PREVIEW_LINES) return content;
    return lines.slice(0, COLLAPSE_PREVIEW_LINES).join('\n');
}
