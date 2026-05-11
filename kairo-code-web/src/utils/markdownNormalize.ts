/**
 * Markdown content normalizers — heuristics that fix common LLM output quirks before
 * the content reaches react-markdown. These run on every assistant message, so they
 * must be cheap and never alter content inside fenced/inline code.
 */

/** Walk content, applying `transform` only to non-code regions. */
function transformOutsideCode(content: string, transform: (text: string) => string): string {
    // 1) Split off fenced code blocks (```...```)
    const fencePattern = /```[\s\S]*?```/g;
    const parts: { kind: 'text' | 'code'; value: string }[] = [];
    let lastIdx = 0;
    for (const m of content.matchAll(fencePattern)) {
        if (m.index! > lastIdx) {
            parts.push({ kind: 'text', value: content.slice(lastIdx, m.index!) });
        }
        parts.push({ kind: 'code', value: m[0] });
        lastIdx = m.index! + m[0].length;
    }
    if (lastIdx < content.length) {
        parts.push({ kind: 'text', value: content.slice(lastIdx) });
    }

    return parts
        .map((p) => {
            if (p.kind === 'code') return p.value;
            // 2) Within text regions, also protect inline `code` spans
            return p.value.replace(/(`[^`\n]+`)|([^`]+)/g, (_, codeSpan, text) =>
                codeSpan ?? transform(text ?? ''),
            );
        })
        .join('');
}

/**
 * Break inline numbered lists into proper markdown list items.
 *
 * LLMs sometimes emit
 *   "P1：  3. Foo — desc 4. Bar — desc 5. Baz — desc"
 * which renders as a single paragraph with no list structure. We detect runs of
 * sequential numbered tokens (e.g., 3, 4, 5) on the same line and split each onto
 * its own paragraph so react-markdown renders an ordered list.
 *
 * Conservative rules:
 *  - Need ≥2 inline matches per line (single ` 1. ` is most likely a sentence like
 *    "Section 1.").
 *  - Numbers must be strictly increasing by 1 (catches genuine list runs, ignores
 *    accidental matches like "1.5 vs 2.0").
 *  - Skip lines already starting with ` *\d+\. ` (already-formatted lists).
 */
export function normalizeInlineNumberedLists(content: string): string {
    return transformOutsideCode(content, (text) => {
        const lines = text.split('\n');
        return lines
            .map((line) => {
                if (/^\s*\d+\.\s/.test(line)) return line;
                const re = /(\s|^)(\d+)\.\s+(?!\d)/g;
                const matches = Array.from(line.matchAll(re));
                if (matches.length < 2) return line;
                const nums = matches.map((m) => parseInt(m[2], 10));
                const sequential = nums.every((n, i) => i === 0 || n === nums[i - 1] + 1);
                if (!sequential) return line;

                let result = line;
                for (let i = matches.length - 1; i >= 0; i--) {
                    const m = matches[i];
                    const start = m.index!;
                    const matchLen = m[0].length;
                    const num = m[2];
                    // Replace " 3. " (or "  3. ") with "\n\n3. " so a blank line precedes
                    // the list — required for react-markdown to start a new <ol>.
                    result =
                        result.slice(0, start) +
                        '\n\n' +
                        num +
                        '. ' +
                        result.slice(start + matchLen);
                }
                return result;
            })
            .join('\n');
    });
}

/**
 * Normalise common LLM-emitted heading punctuation: `**P1 （尽快处理）**：` followed by
 * inline content reads better as a sub-heading. We don't rewrite — that's too invasive —
 * but we do ensure that after a bold-prefixed colon there's at least a soft break so the
 * subsequent content isn't glued to the colon.
 */
export function normalizeBoldColonBreaks(content: string): string {
    return transformOutsideCode(content, (text) =>
        text.replace(/(\*\*[^*\n]+\*\*[：:])\s{2,}(?=\S)/g, '$1\n\n'),
    );
}

/** Run all assistant-message normalizers in order. */
export function normalizeAssistantMarkdown(content: string): string {
    if (!content) return content;
    let out = normalizeInlineNumberedLists(content);
    out = normalizeBoldColonBreaks(out);
    return out;
}
