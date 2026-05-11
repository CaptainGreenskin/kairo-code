const PREFS_KEY = 'kairo-user-prefs';

export type ActivityView = 'files' | 'search' | 'git' | 'workspaces';

export interface UserPrefs {
    theme?: 'dark' | 'light';
    model?: string;
    fileTreeOpen?: boolean;
    sidebarWidth?: number;
    filesWidth?: number;
    primarySidebarOpen?: boolean;
    bottomPanelOpen?: boolean;
    activityView?: ActivityView;
    bottomHeight?: number;
    /** VS Code-style: chat lives in the right sidebar. Toggleable. */
    chatSidebarOpen?: boolean;
    chatWidth?: number;
    /** Sessions opened as chat tabs (Cursor-style) — preserved order. */
    chatOpenTabs?: string[];
    /** Currently focused chat tab session id. */
    chatActiveSession?: string;
    /** Width of the embedded Sessions panel inside the chat aside. */
    chatSessionsWidth?: number;
    /** Whether the Sessions panel inside the chat aside is visible. */
    chatSessionsOpen?: boolean;
    /** Whether the sticky TodoListPanel is collapsed (only progress bar visible). */
    todoPanelCollapsed?: boolean;
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
