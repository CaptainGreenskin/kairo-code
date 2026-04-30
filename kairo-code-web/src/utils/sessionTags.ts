const TAGS_KEY = 'kairo-session-tags';

type TagMap = Record<string, string[]>;

function loadTagMap(): TagMap {
    try {
        return JSON.parse(localStorage.getItem(TAGS_KEY) ?? '{}');
    } catch {
        return {};
    }
}

function saveTagMap(map: TagMap): void {
    localStorage.setItem(TAGS_KEY, JSON.stringify(map));
}

export function getSessionTags(sessionId: string): string[] {
    return loadTagMap()[sessionId] ?? [];
}

export function addSessionTag(sessionId: string, tag: string): void {
    const map = loadTagMap();
    const tags = map[sessionId] ?? [];
    if (!tags.includes(tag)) {
        map[sessionId] = [...tags, tag.trim().toLowerCase()];
        saveTagMap(map);
    }
}

export function removeSessionTag(sessionId: string, tag: string): void {
    const map = loadTagMap();
    map[sessionId] = (map[sessionId] ?? []).filter(t => t !== tag);
    saveTagMap(map);
}

export function getAllTags(): string[] {
    const map = loadTagMap();
    const set = new Set<string>();
    Object.values(map).forEach(tags => tags.forEach(t => set.add(t)));
    return [...set].sort();
}
