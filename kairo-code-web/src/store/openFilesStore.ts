import { create } from 'zustand';

/** A file tab — identity is the filesystem path. */
export interface FileTab {
    kind: 'file';
    id: string;   // === path (back-compat)
    path: string;
    /** 1-based line to scroll to on next focus, then cleared. */
    gotoLine?: number;
}

/** A non-file tab showing one expert/step's full execution trace. */
export interface ExpertStepTab {
    kind: 'expertStep';
    id: string;   // `expert:${teamId}:${stepId}`
    teamId: string;
    stepId: string;
    title: string;
}

export type OpenTab = FileTab | ExpertStepTab;

function expertTabId(teamId: string, stepId: string): string {
    return `expert:${teamId}:${stepId}`;
}

interface OpenFilesState {
    tabs: OpenTab[];
    /** Identity of the active tab (a path for file tabs, `expert:…` for expert tabs). */
    activePath: string | null;

    openFile: (path: string, gotoLine?: number) => void;
    openExpertStepTab: (args: { teamId: string; stepId: string; title: string }) => void;
    closeFile: (id: string) => void;
    setActive: (id: string) => void;
    closeAll: () => void;
    /** Clear gotoLine on the given file tab once Monaco has scrolled. */
    consumeGotoLine: (id: string) => void;
}

export const useOpenFilesStore = create<OpenFilesState>()((set, get) => ({
    tabs: [],
    activePath: null,

    openFile: (path, gotoLine) => {
        const { tabs } = get();
        const idx = tabs.findIndex((t) => t.id === path);
        const tab: FileTab = { kind: 'file', id: path, path, gotoLine };
        if (idx >= 0) {
            const next = tabs.slice();
            next[idx] = tab;
            set({ tabs: next, activePath: path });
        } else {
            set({ tabs: [...tabs, tab], activePath: path });
        }
    },

    openExpertStepTab: ({ teamId, stepId, title }) => {
        const id = expertTabId(teamId, stepId);
        const { tabs } = get();
        if (tabs.some((t) => t.id === id)) {
            set({ activePath: id });
            return;
        }
        const tab: ExpertStepTab = { kind: 'expertStep', id, teamId, stepId, title };
        set({ tabs: [...tabs, tab], activePath: id });
    },

    closeFile: (id) => {
        const { tabs, activePath } = get();
        const idx = tabs.findIndex((t) => t.id === id);
        if (idx < 0) return;
        const next = tabs.filter((t) => t.id !== id);
        let nextActive = activePath;
        if (activePath === id) {
            // Pick neighbor: prefer the next tab, fall back to previous.
            nextActive = next[idx]?.id ?? next[idx - 1]?.id ?? null;
        }
        set({ tabs: next, activePath: nextActive });
    },

    setActive: (id) => {
        const { tabs } = get();
        if (tabs.some((t) => t.id === id)) {
            set({ activePath: id });
        }
    },

    closeAll: () => set({ tabs: [], activePath: null }),

    consumeGotoLine: (id) => {
        const { tabs } = get();
        const idx = tabs.findIndex((t) => t.id === id);
        if (idx < 0) return;
        const t = tabs[idx];
        if (t.kind !== 'file' || t.gotoLine === undefined) return;
        const next = tabs.slice();
        next[idx] = { kind: 'file', id: t.id, path: t.path };
        set({ tabs: next });
    },
}));
