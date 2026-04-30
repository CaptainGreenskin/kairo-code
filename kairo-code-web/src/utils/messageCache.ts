import type { Message } from '@/types/agent';

const KEY_PREFIX = 'kairo-msgs-';
const MAX_MESSAGES = 200; // 防止 sessionStorage 满

export function saveMessages(sessionId: string, messages: Message[]): void {
    if (!sessionId || messages.length === 0) return;
    try {
        const toSave = messages.slice(-MAX_MESSAGES); // 保留最新 200 条
        sessionStorage.setItem(KEY_PREFIX + sessionId, JSON.stringify(toSave));
    } catch {
        // sessionStorage full or unavailable — silently ignore
    }
}

export function loadMessages(sessionId: string): Message[] {
    try {
        const raw = sessionStorage.getItem(KEY_PREFIX + sessionId);
        if (!raw) return [];
        return JSON.parse(raw) as Message[];
    } catch {
        return [];
    }
}

export function clearMessages(sessionId: string): void {
    sessionStorage.removeItem(KEY_PREFIX + sessionId);
}
