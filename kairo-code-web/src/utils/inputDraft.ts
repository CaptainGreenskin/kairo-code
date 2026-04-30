const PREFIX = 'kairo-draft:';
const MAX_DRAFT_LENGTH = 10_000;

export function saveDraft(sessionId: string, text: string): void {
    if (!sessionId) return;
    if (!text.trim()) {
        localStorage.removeItem(PREFIX + sessionId);
    } else {
        localStorage.setItem(PREFIX + sessionId, text.slice(0, MAX_DRAFT_LENGTH));
    }
}

export function loadDraft(sessionId: string): string {
    if (!sessionId) return '';
    return localStorage.getItem(PREFIX + sessionId) ?? '';
}

export function clearDraft(sessionId: string): void {
    if (!sessionId) return;
    localStorage.removeItem(PREFIX + sessionId);
}
