const PREFIX = 'kairo-bookmarks:';

export function getBookmarks(sessionId: string): string[] {
    if (!sessionId) return [];
    try {
        return JSON.parse(localStorage.getItem(PREFIX + sessionId) ?? '[]') as string[];
    } catch {
        return [];
    }
}

export function isBookmarked(sessionId: string, messageId: string): boolean {
    return getBookmarks(sessionId).includes(messageId);
}

export function toggleBookmark(sessionId: string, messageId: string): boolean {
    const bookmarks = getBookmarks(sessionId);
    let next: string[];
    let added: boolean;
    if (bookmarks.includes(messageId)) {
        next = bookmarks.filter(id => id !== messageId);
        added = false;
    } else {
        next = [...bookmarks, messageId];
        added = true;
    }
    localStorage.setItem(PREFIX + sessionId, JSON.stringify(next));
    return added;
}

export function clearBookmarks(sessionId: string): void {
    if (!sessionId) return;
    localStorage.removeItem(PREFIX + sessionId);
}
