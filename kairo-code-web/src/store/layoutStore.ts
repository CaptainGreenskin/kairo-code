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
