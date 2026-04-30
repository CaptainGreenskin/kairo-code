const PREFS_KEY = 'kairo-user-prefs';

export interface UserPrefs {
    theme?: 'dark' | 'light';
    model?: string;
    fileTreeOpen?: boolean;
    sidebarWidth?: number;
}

export function loadPrefs(): UserPrefs {
    try {
        return JSON.parse(localStorage.getItem(PREFS_KEY) ?? '{}');
    } catch {
        return {};
    }
}

export function savePrefs(partial: Partial<UserPrefs>): void {
    const current = loadPrefs();
    localStorage.setItem(PREFS_KEY, JSON.stringify({ ...current, ...partial }));
}

export function savePref<K extends keyof UserPrefs>(key: K, value: UserPrefs[K]): void {
    savePrefs({ [key]: value } as Partial<UserPrefs>);
}
