const STORAGE_KEY = 'kairo-session-names';

function load(): Record<string, string> {
    try {
        return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}');
    } catch {
        return {};
    }
}

export function getSessionName(sessionId: string): string | null {
    return load()[sessionId] ?? null;
}

export function setSessionName(sessionId: string, name: string): void {
    const map = load();
    const trimmed = name.trim();
    if (trimmed) {
        map[sessionId] = trimmed;
        localStorage.setItem(STORAGE_KEY, JSON.stringify(map));
    } else {
        delete map[sessionId];
        localStorage.setItem(STORAGE_KEY, JSON.stringify(map));
    }
}

export function removeSessionName(sessionId: string): void {
    const map = load();
    delete map[sessionId];
    localStorage.setItem(STORAGE_KEY, JSON.stringify(map));
}
