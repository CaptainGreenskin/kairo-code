import { create } from 'zustand';
import { loadPrefs, savePref, type ActivityView } from '@/utils/userPrefs';

const prefs = loadPrefs();

interface LayoutState {
    primarySidebarOpen: boolean;
    bottomPanelOpen: boolean;
    fileTreeOpen: boolean;
    activityView: ActivityView;
    sidebarWidth: number;
    bottomHeight: number;
    filesWidth: number;
    chatSidebarOpen: boolean;
    chatWidth: number;
    /** Width of the Sessions strip embedded inside the chat aside. */
    chatSessionsWidth: number;
    /** Whether the Sessions strip is rendered (Cursor-style — sessions live with chat). */
    chatSessionsOpen: boolean;
    /** Monotonic counter bumped on agent file writes — triggers FileTreePanel reload. */
    fileTreeRefreshKey: number;
    /** File paths recently modified by agent tools — cleared after 5 s. */
    recentlyModifiedFiles: Set<string>;

    togglePrimarySidebar: () => void;
    toggleBottomPanel: () => void;
    toggleFileTree: () => void;
    toggleChatSidebar: () => void;
    toggleChatSessions: () => void;
    setActivityView: (view: ActivityView) => void;
    setSidebarWidth: (w: number) => void;
    setBottomHeight: (h: number) => void;
    setFilesWidth: (w: number) => void;
    setChatWidth: (w: number) => void;
    setChatSessionsWidth: (w: number) => void;
    /** Activity-Bar click: switch view if different, close sidebar if same. */
    selectActivity: (view: ActivityView) => void;
    /** Bump file tree refresh key and mark paths as recently modified. */
    bumpFileTreeRefresh: (paths: string[]) => void;
}

export const useLayoutStore = create<LayoutState>()((set, get) => ({
    primarySidebarOpen: prefs.primarySidebarOpen ?? true,
    bottomPanelOpen: prefs.bottomPanelOpen ?? false,
    fileTreeOpen: prefs.fileTreeOpen ?? false,
    // Migrate the now-removed 'sessions' view (legacy installs) → 'files'.
    activityView: ((prefs.activityView as string) === 'sessions' ? 'files' : (prefs.activityView ?? 'files')) as ActivityView,
    sidebarWidth: prefs.sidebarWidth ?? 280,
    bottomHeight: prefs.bottomHeight ?? 240,
    filesWidth: prefs.filesWidth ?? 320,
    chatSidebarOpen: prefs.chatSidebarOpen ?? true,
    chatWidth: prefs.chatWidth ?? 640,
    chatSessionsWidth: prefs.chatSessionsWidth ?? 220,
    chatSessionsOpen: prefs.chatSessionsOpen ?? false,
    fileTreeRefreshKey: 0,
    recentlyModifiedFiles: new Set<string>(),

    togglePrimarySidebar: () => {
        const next = !get().primarySidebarOpen;
        set({ primarySidebarOpen: next });
        savePref('primarySidebarOpen', next);
    },
    toggleBottomPanel: () => {
        const next = !get().bottomPanelOpen;
        set({ bottomPanelOpen: next });
        savePref('bottomPanelOpen', next);
    },
    toggleFileTree: () => {
        const next = !get().fileTreeOpen;
        set({ fileTreeOpen: next });
        savePref('fileTreeOpen', next);
    },
    setActivityView: (view) => {
        set({ activityView: view });
        savePref('activityView', view);
    },
    setSidebarWidth: (w) => {
        set({ sidebarWidth: w });
        savePref('sidebarWidth', w);
    },
    setBottomHeight: (h) => {
        set({ bottomHeight: h });
        savePref('bottomHeight', h);
    },
    setFilesWidth: (w) => {
        set({ filesWidth: w });
        savePref('filesWidth', w);
    },
    toggleChatSidebar: () => {
        const next = !get().chatSidebarOpen;
        set({ chatSidebarOpen: next });
        savePref('chatSidebarOpen', next);
    },
    toggleChatSessions: () => {
        const next = !get().chatSessionsOpen;
        set({ chatSessionsOpen: next });
        savePref('chatSessionsOpen', next);
    },
    setChatWidth: (w) => {
        set({ chatWidth: w });
        savePref('chatWidth', w);
    },
    setChatSessionsWidth: (w) => {
        set({ chatSessionsWidth: w });
        savePref('chatSessionsWidth', w);
    },
    bumpFileTreeRefresh: (paths) => {
        const current = get().recentlyModifiedFiles;
        const next = new Set(current);
        for (const p of paths) next.add(p);
        set({ fileTreeRefreshKey: get().fileTreeRefreshKey + 1, recentlyModifiedFiles: next });
        setTimeout(() => {
            const updated = get().recentlyModifiedFiles;
            const cleaned = new Set(updated);
            for (const p of paths) cleaned.delete(p);
            set({ recentlyModifiedFiles: cleaned });
        }, 5000);
    },
    selectActivity: (view) => {
        const { activityView, primarySidebarOpen } = get();
        if (activityView === view && primarySidebarOpen) {
            // Click already-active icon: close sidebar (keep view memory)
            set({ primarySidebarOpen: false });
            savePref('primarySidebarOpen', false);
        } else {
            set({ activityView: view, primarySidebarOpen: true });
            savePref('activityView', view);
            savePref('primarySidebarOpen', true);
        }
    },
}));
