import type { Message } from '@/types/agent';

const KEY_PREFIX = 'kairo-msg-';
const TS_PREFIX = 'kairo-msg-ts-';
const MAX_MESSAGES = 200;
const EXPIRY_MS = 30 * 24 * 60 * 60 * 1000; // 30 days

/** Remove keys whose last-access timestamp is older than 30 days. */
function cleanupExpired(): void {
    const now = Date.now();
    const toRemove: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (!key?.startsWith(TS_PREFIX)) continue;
        const ts = Number(localStorage.getItem(key));
        if (isNaN(ts) || now - ts > EXPIRY_MS) {
            const sessionId = key.slice(TS_PREFIX.length);
            toRemove.push(KEY_PREFIX + sessionId);
            toRemove.push(key);
        }
    }
    for (const k of toRemove) {
        try { localStorage.removeItem(k); } catch { /* ignore */ }
    }
}

/** Guard against localStorage quota overflow. */
function safeSetItem(key: string, value: string): void {
    try {
        localStorage.setItem(key, value);
    } catch {
        // QuotaExceededError — remove oldest expired/expirable entries and retry once.
        try {
            cleanupExpired();
            localStorage.setItem(key, value);
        } catch {
            // Still full — silently give up.
        }
    }
}

export function saveMessages(sessionId: string, messages: Message[]): void {
    if (!sessionId || messages.length === 0) return;
    const toSave = messages.slice(-MAX_MESSAGES);
    safeSetItem(KEY_PREFIX + sessionId, JSON.stringify(toSave));
    safeSetItem(TS_PREFIX + sessionId, String(Date.now()));
}

export function loadMessages(sessionId: string): Message[] {
    try {
        const raw = localStorage.getItem(KEY_PREFIX + sessionId);
        if (!raw) return [];
        // Touch the timestamp so recent sessions stay alive.
        safeSetItem(TS_PREFIX + sessionId, String(Date.now()));
        return JSON.parse(raw) as Message[];
    } catch {
        return [];
    }
}

export function clearMessages(sessionId: string): void {
    try {
        localStorage.removeItem(KEY_PREFIX + sessionId);
        localStorage.removeItem(TS_PREFIX + sessionId);
    } catch {
        // ignore
    }
}

/** Scan and remove expired message entries on module load. */
cleanupExpired();
