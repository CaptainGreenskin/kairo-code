import { Files, Search, GitBranch, Brain, Zap } from 'lucide-react';
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
];

interface QuickAction {
    id: string;
    label: string;
    icon: typeof Brain;
    onClick: () => void;
}

export function ActivityBar({ onOpenEvolution, onOpenHooks }: {
    onOpenEvolution?: () => void;
    onOpenHooks?: () => void;
}) {
    const activeView = useLayoutStore((s) => s.activityView);
    const sidebarOpen = useLayoutStore((s) => s.primarySidebarOpen);
    const selectActivity = useLayoutStore((s) => s.selectActivity);

    const quickActions: QuickAction[] = [
        { id: 'evolution', label: 'Self-Evolution Lessons', icon: Brain, onClick: () => onOpenEvolution?.() },
        { id: 'hooks', label: 'Hook Configuration', icon: Zap, onClick: () => onOpenHooks?.() },
    ];

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

            <div className="flex-1" />

            {/* Quick access to key capabilities */}
            <div className="flex flex-col items-center gap-1 border-t border-[var(--border)] pt-2 mt-1">
                {quickActions.map((action) => {
                    const Icon = action.icon;
                    return (
                        <button
                            key={action.id}
                            onClick={action.onClick}
                            className="w-10 h-10 flex items-center justify-center rounded text-[var(--text-muted)] hover:text-[var(--accent)] hover:bg-[var(--bg-hover)] transition-colors"
                            title={action.label}
                            aria-label={action.label}
                        >
                            <Icon size={18} />
                        </button>
                    );
                })}
            </div>
        </nav>
    );
}
