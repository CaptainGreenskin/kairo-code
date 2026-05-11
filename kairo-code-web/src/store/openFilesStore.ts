import { create } from 'zustand';

export interface OpenFile {
    path: string;
    /** 1-based line to scroll to on next focus, then cleared. */
    gotoLine?: number;
}

interface OpenFilesState {
    tabs: OpenFile[];
    activePath: string | null;

    openFile: (path: string, gotoLine?: number) => void;
    closeFile: (path: string) => void;
    setActive: (path: string) => void;
    closeAll: () => void;
    /** Clear gotoLine on the active tab once Monaco has scrolled. */
    consumeGotoLine: (path: string) => void;
}

export const useOpenFilesStore = create<OpenFilesState>()((set, get) => ({
    tabs: [],
    activePath: null,

    openFile: (path, gotoLine) => {
        const { tabs } = get();
        const idx = tabs.findIndex((t) => t.path === path);
        if (idx >= 0) {
            const next = tabs.slice();
            next[idx] = { path, gotoLine };
            set({ tabs: next, activePath: path });
        } else {
            set({ tabs: [...tabs, { path, gotoLine }], activePath: path });
        }
    },

    closeFile: (path) => {
        const { tabs, activePath } = get();
        const idx = tabs.findIndex((t) => t.path === path);
        if (idx < 0) return;
        const next = tabs.filter((t) => t.path !== path);
        let nextActive = activePath;
        if (activePath === path) {
            // Pick neighbor: prefer the next tab, fall back to previous.
            nextActive = next[idx]?.path ?? next[idx - 1]?.path ?? null;
        }
        set({ tabs: next, activePath: nextActive });
    },

    setActive: (path) => {
        const { tabs } = get();
        if (tabs.some((t) => t.path === path)) {
            set({ activePath: path });
        }
    },

    closeAll: () => set({ tabs: [], activePath: null }),

    consumeGotoLine: (path) => {
        const { tabs } = get();
        const idx = tabs.findIndex((t) => t.path === path);
        if (idx < 0 || tabs[idx].gotoLine === undefined) return;
        const next = tabs.slice();
        next[idx] = { path: tabs[idx].path };
        set({ tabs: next });
    },
}));
