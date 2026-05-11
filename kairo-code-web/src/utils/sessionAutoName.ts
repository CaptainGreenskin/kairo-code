import { setSessionName, getSessionName } from './sessionNames';

/**
 * Drop `<system-reminder>...</system-reminder>` blocks before naming. These get
 * prepended for plan mode etc., and surfacing them as a session title yields
 * meaningless "<system-reminder>" labels in the sidebar.
 */
function stripSystemReminders(text: string): string {
    return text.replace(/<system-reminder>[\s\S]*?<\/system-reminder>\s*/g, '').trim();
}

export async function autoNameSession(sessionId: string, firstMessage: string): Promise<string | null> {
    // Don't overwrite existing name
    if (getSessionName(sessionId)) return null;
    const cleaned = stripSystemReminders(firstMessage);
    if (!cleaned) return null;

    try {
        const res = await fetch(`/api/sessions/${sessionId}/auto-name`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ firstMessage: cleaned }),
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
