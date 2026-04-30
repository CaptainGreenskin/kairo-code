const PREFIX = 'kairo-input-history:';
const MAX_HISTORY = 100;

export function getHistory(sessionId: string): string[] {
    if (!sessionId) return [];
    try {
        return JSON.parse(localStorage.getItem(PREFIX + sessionId) ?? '[]') as string[];
    } catch {
        return [];
    }
}

/**
 * Appends an entry to the history for the given session.
 * Deduplicates consecutive identical entries. Trims to MAX_HISTORY.
 */
export function pushHistory(sessionId: string, entry: string): void {
    if (!sessionId || !entry.trim()) return;
    const history = getHistory(sessionId);
    if (history[history.length - 1] === entry) return; // skip consecutive duplicate
    history.push(entry);
    if (history.length > MAX_HISTORY) history.splice(0, history.length - MAX_HISTORY);
    localStorage.setItem(PREFIX + sessionId, JSON.stringify(history));
}

export function clearHistory(sessionId: string): void {
    if (!sessionId) return;
    localStorage.removeItem(PREFIX + sessionId);
}
