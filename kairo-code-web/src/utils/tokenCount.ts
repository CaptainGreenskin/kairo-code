// Simple tiktoken-style estimation: CJK characters weighted higher
// For frontend display only, not intended to be precise

export function estimateTokens(text: string): number {
    if (!text) return 0;
    let count = 0;
    for (const ch of text) {
        const code = ch.codePointAt(0) ?? 0;
        // CJK Unified Ideographs, Hiragana, Katakana, Hangul
        if ((code >= 0x4E00 && code <= 0x9FFF) ||
            (code >= 0x3040 && code <= 0x30FF) ||
            (code >= 0xAC00 && code <= 0xD7A3)) {
            count += 2; // 2 "half tokens"
        } else {
            count += 1; // 1 "half token"
        }
    }
    return Math.ceil(count / 4); // 4 half-tokens ≈ 1 token
}

export function estimateMessagesTokens(messages: Array<{ role: string; content: string }>): number {
    return messages.reduce((sum, m) => sum + estimateTokens(m.content ?? '') + 4, 0);
    // +4 per message for role/metadata overhead
}

export function formatTokenCount(count: number): string {
    if (count < 1000) return `${count}`;
    if (count < 10000) return `${(count / 1000).toFixed(1)}k`;
    return `${Math.round(count / 1000)}k`;
}
