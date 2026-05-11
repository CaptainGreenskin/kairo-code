/**
 * Mirrors {@code SessionDiagnostics} on the backend
 * (kairo-code-service/.../SessionDiagnostics.java). Lets the dev panel show the
 * authoritative server-side view of a session — useful when the WS pipe is
 * silent for a while and the user wants to know whether the agent is hung
 * vs whether the connection just dropped.
 */
export interface SessionDiagnostics {
    sessionId: string;
    running: boolean;
    lastEventAt: number;
    msSinceLastEvent: number;
    eventCounts: Record<string, number>;
    wsClients: number;
}

const API_BASE = '/api';

/**
 * Fetch live diagnostics for a session.
 *
 * Returns null when the server replies 404 (session unknown) so the caller can
 * differentiate "no such session" from a transient network error.
 */
export async function getDiagnostics(sessionId: string): Promise<SessionDiagnostics | null> {
    const response = await fetch(`${API_BASE}/sessions/${sessionId}/diagnostics`);
    if (response.status === 404) return null;
    if (!response.ok) {
        const text = await response.text();
        throw new Error(`HTTP ${response.status}: ${text || response.statusText}`);
    }
    return response.json() as Promise<SessionDiagnostics>;
}
