const HISTORY_KEY_PREFIX = 'kairo-input-history:';
const MAX_HISTORY = 50;

function historyKey(sessionId: string): string {
    return HISTORY_KEY_PREFIX + sessionId;
}

export function loadHistory(sessionId: string): string[] {
    try {
        return JSON.parse(localStorage.getItem(historyKey(sessionId)) ?? '[]');
    } catch {
        return [];
    }
}

export function pushHistory(sessionId: string, text: string): void {
    const trimmed = text.trim();
    if (!trimmed) return;
    const history = loadHistory(sessionId).filter(h => h !== trimmed);
    history.unshift(trimmed);
    localStorage.setItem(historyKey(sessionId), JSON.stringify(history.slice(0, MAX_HISTORY)));
}

export function clearHistory(sessionId: string): void {
    localStorage.removeItem(historyKey(sessionId));
}
