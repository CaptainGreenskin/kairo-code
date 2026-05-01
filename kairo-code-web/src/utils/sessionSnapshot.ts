import type { Message } from '@/types/agent';

/**
 * Snapshot persistence utilities backed by the server-side
 * /api/sessions/{id}/snapshot endpoints. Snapshots survive a browser refresh
 * by being written to disk under the agent's working directory.
 */

export interface SessionSnapshot {
    sessionId: string;
    name: string;
    savedAt: number;
    messages: Message[];
}

export interface SnapshotMeta {
    sessionId: string;
    name: string;
    savedAt: number;
    messageCount: number;
}

/** Persist a session snapshot to the server. Returns true on success. */
export async function saveSnapshot(
    sessionId: string,
    name: string,
    messages: Message[],
): Promise<boolean> {
    if (!sessionId || messages.length === 0) return false;
    try {
        const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/snapshot`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId, name, messages }),
        });
        return res.ok;
    } catch {
        return false;
    }
}

/** Load a previously persisted snapshot, or null if missing/error. */
export async function loadSnapshot(sessionId: string): Promise<SessionSnapshot | null> {
    if (!sessionId) return null;
    try {
        const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/snapshot`);
        if (!res.ok) return null;
        return (await res.json()) as SessionSnapshot;
    } catch {
        return null;
    }
}

/** List metadata for all persisted snapshots, newest first. */
export async function listSnapshots(): Promise<SnapshotMeta[]> {
    try {
        const res = await fetch('/api/sessions/snapshots');
        if (!res.ok) return [];
        return (await res.json()) as SnapshotMeta[];
    } catch {
        return [];
    }
}

/** Delete a persisted snapshot from disk. */
export async function deleteSnapshot(sessionId: string): Promise<boolean> {
    if (!sessionId) return false;
    try {
        const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/snapshot`, {
            method: 'DELETE',
        });
        return res.ok;
    } catch {
        return false;
    }
}

const LAST_SESSION_KEY = 'kairo-last-session';

/** Remember the most recently active session id (used for auto-restore on refresh). */
export function setLastSessionId(id: string): void {
    try {
        localStorage.setItem(LAST_SESSION_KEY, id);
    } catch {
        // localStorage may be unavailable (private mode); silently ignore
    }
}

/** Read the last-active session id, or null if none. */
export function getLastSessionId(): string | null {
    try {
        return localStorage.getItem(LAST_SESSION_KEY);
    } catch {
        return null;
    }
}

/** Forget the last-active session id (used when explicitly closing a session). */
export function clearLastSessionId(): void {
    try {
        localStorage.removeItem(LAST_SESSION_KEY);
    } catch {
        // ignore
    }
}
