const STORAGE_KEY = 'kairo-session-names';

function load(): Record<string, string> {
    try {
        return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}');
    } catch {
        return {};
    }
}

/**
 * A persisted session name is "bad" if it leaked the system-reminder envelope
 * (older builds named sessions from the raw first message including the
 * plan-mode preamble). Treat those as unnamed so the auto-namer can retry on
 * the next message and the sidebar falls back to the short session id.
 */
function isBadName(name: string | undefined | null): boolean {
    if (!name) return true;
    return /<system-reminder>/i.test(name);
}

export function getSessionName(sessionId: string): string | null {
    const stored = load()[sessionId];
    if (isBadName(stored)) return null;
    return stored ?? null;
}

export function setSessionName(sessionId: string, name: string): void {
    const map = load();
    const trimmed = name.trim();
    if (trimmed && !isBadName(trimmed)) {
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
