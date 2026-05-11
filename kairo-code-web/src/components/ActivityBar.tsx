import { Files, Search, GitBranch, Layers } from 'lucide-react';
import { useLayoutStore } from '@store/layoutStore';
import type { ActivityView } from '@utils/userPrefs';

interface ActivityItem {
    id: ActivityView;
    label: string;
    icon: typeof Files;
    shortcut?: string;
}

const ITEMS: ActivityItem[] = [
    { id: 'files', label: 'Explorer', icon: Files, shortcut: '⌘⇧E' },
    { id: 'search', label: 'Search', icon: Search, shortcut: '⌘F' },
    { id: 'git', label: 'Source Control', icon: GitBranch },
    { id: 'workspaces', label: 'Workspaces', icon: Layers },
];

export function ActivityBar() {
    const activeView = useLayoutStore((s) => s.activityView);
    const sidebarOpen = useLayoutStore((s) => s.primarySidebarOpen);
    const selectActivity = useLayoutStore((s) => s.selectActivity);

    return (
        <nav
            className="w-12 shrink-0 flex flex-col items-center bg-[var(--bg-secondary)] border-r border-[var(--border)] py-2 gap-1"
            aria-label="Activity Bar"
        >
            {ITEMS.map((item) => {
                const Icon = item.icon;
                const isActive = sidebarOpen && activeView === item.id;
                return (
                    <button
                        key={item.id}
                        onClick={() => selectActivity(item.id)}
                        className={`relative w-10 h-10 flex items-center justify-center rounded transition-colors group ${
                            isActive
                                ? 'text-[var(--text-primary)]'
                                : 'text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)]'
                        }`}
                        title={item.shortcut ? `${item.label} (${item.shortcut})` : item.label}
                        aria-label={item.label}
                        aria-pressed={isActive}
                    >
                        {isActive && (
                            <span className="absolute left-0 top-1 bottom-1 w-0.5 bg-[var(--accent)] rounded-r" />
                        )}
                        <Icon size={20} />
                    </button>
                );
            })}
        </nav>
    );
}
