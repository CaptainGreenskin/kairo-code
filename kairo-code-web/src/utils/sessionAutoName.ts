import { setSessionName, getSessionName } from './sessionNames';

export async function autoNameSession(sessionId: string, firstMessage: string): Promise<string | null> {
    // Don't overwrite existing name
    if (getSessionName(sessionId)) return null;
    if (!firstMessage.trim()) return null;

    try {
        const res = await fetch(`/api/sessions/${sessionId}/auto-name`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ firstMessage }),
        });
        if (!res.ok) return null;
        const { name } = await res.json() as { name: string };
        if (name && name !== 'New Session') {
            setSessionName(sessionId, name);
            return name;
        }
    } catch { /* ignore network errors */ }
    return null;
}
