export type Reaction = 'up' | 'down' | null;

export interface ReactionEntry {
    reaction: Reaction;
    note?: string;
    ts: number;
}

const KEY = 'kairo-message-reactions';

function load(): Record<string, ReactionEntry> {
    try { return JSON.parse(localStorage.getItem(KEY) ?? '{}'); }
    catch { return {}; }
}

export function getReaction(messageId: string): ReactionEntry | null {
    return load()[messageId] ?? null;
}

export function setReaction(messageId: string, reaction: Reaction, note?: string): void {
    const data = load();
    if (reaction === null) {
        delete data[messageId];
    } else {
        data[messageId] = { reaction, note, ts: Date.now() };
    }
    localStorage.setItem(KEY, JSON.stringify(data));
}

export function getAllReactions(): Record<string, ReactionEntry> {
    return load();
}
