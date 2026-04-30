const PINS_KEY = 'kairo-session-pins';

export function getPinnedSessions(): string[] {
    try {
        const raw = localStorage.getItem(PINS_KEY);
        if (!raw) return [];
        return JSON.parse(raw) as string[];
    } catch {
        return [];
    }
}

export function pinSession(sessionId: string): void {
    const pins = getPinnedSessions();
    if (!pins.includes(sessionId)) {
        localStorage.setItem(PINS_KEY, JSON.stringify([sessionId, ...pins]));
    }
}

export function unpinSession(sessionId: string): void {
    const pins = getPinnedSessions().filter(id => id !== sessionId);
    localStorage.setItem(PINS_KEY, JSON.stringify(pins));
}

export function isSessionPinned(sessionId: string): boolean {
    return getPinnedSessions().includes(sessionId);
}
