import { useLayoutStore } from '@store/layoutStore';
import type { ActivityView } from '@utils/userPrefs';
import type { ReactNode } from 'react';

interface PrimarySidebarProps {
    /** Per-view content slots — each is the corresponding view's embedded body. */
    filesView: ReactNode;
    searchView: ReactNode;
    gitView: ReactNode;
    workspacesView: ReactNode;
}

const TITLES: Record<ActivityView, string> = {
    files: 'Explorer',
    search: 'Search',
    git: 'Source Control',
    workspaces: 'Workspaces',
};

export function PrimarySidebar({
    filesView,
    searchView,
    gitView,
    workspacesView,
}: PrimarySidebarProps) {
    const view = useLayoutStore((s) => s.activityView);
    const open = useLayoutStore((s) => s.primarySidebarOpen);

    if (!open) return null;

    let body: ReactNode = null;
    switch (view) {
        case 'files':
            body = filesView;
            break;
        case 'search':
            body = searchView;
            break;
        case 'git':
            body = gitView;
            break;
        case 'workspaces':
            body = workspacesView;
            break;
    }

    return (
        <aside
            className="flex flex-col h-full bg-[var(--bg-primary)] border-r border-[var(--border)] overflow-hidden"
            aria-label={`${TITLES[view]} sidebar`}
        >
            <header className="flex items-center justify-between px-3 py-2 border-b border-[var(--border)] bg-[var(--bg-secondary)] shrink-0">
                <span className="text-[11px] font-semibold uppercase tracking-wide text-[var(--text-secondary)]">
                    {TITLES[view]}
                </span>
            </header>
            {/* flex-col so embedded children that use `flex-1` resolve their height
                against this container — without it, FileTreePanel etc. overflow past
                the BottomPanel and bleed into the shell area. */}
            <div className="flex-1 min-h-0 overflow-hidden flex flex-col">{body}</div>
        </aside>
    );
}
