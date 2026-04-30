// 常见模型的 context window（token 数）
// 保守估计，仅用于 UI 进度条显示
const MODEL_CONTEXT_WINDOWS: Record<string, number> = {
    'gpt-4o': 128_000,
    'gpt-4o-mini': 128_000,
    'gpt-4': 8_192,
    'gpt-4-turbo': 128_000,
    'claude-3-5-sonnet': 200_000,
    'claude-3-5-haiku': 200_000,
    'claude-3-opus': 200_000,
    'claude-sonnet-4': 200_000,
    'claude-opus-4': 200_000,
    'glm-4': 128_000,
    'glm-4-flash': 128_000,
};

const DEFAULT_CONTEXT_WINDOW = 128_000;

export function getContextWindow(model: string): number {
    if (!model) return DEFAULT_CONTEXT_WINDOW;
    const lower = model.toLowerCase();
    for (const [key, size] of Object.entries(MODEL_CONTEXT_WINDOWS)) {
        if (lower.includes(key)) return size;
    }
    return DEFAULT_CONTEXT_WINDOW;
}

/**
 * Returns the usage ratio (0–1) clamped to [0, 1].
 */
export function getUsageRatio(usedTokens: number, model: string): number {
    const window = getContextWindow(model);
    return Math.min(1, Math.max(0, usedTokens / window));
}

/**
 * Returns a color class based on usage ratio.
 * <50%: green, 50-80%: yellow, >80%: red
 */
export function getUsageColorClass(ratio: number): 'green' | 'yellow' | 'red' {
    if (ratio >= 0.8) return 'red';
    if (ratio >= 0.5) return 'yellow';
    return 'green';
}

/**
 * Formats token count with K suffix.
 */
export function formatTokens(n: number): string {
    if (n >= 1000) return `${(n / 1000).toFixed(1)}K`;
    return String(n);
}
