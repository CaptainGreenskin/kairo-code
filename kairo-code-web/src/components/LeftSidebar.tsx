import { type ComponentProps } from 'react';
import { SessionSidebar } from './SessionSidebar';

type SessionSidebarProps = Omit<ComponentProps<typeof SessionSidebar>, 'embedded'>;

interface LeftSidebarProps {
    sessionProps: SessionSidebarProps;
    /** Width in px. Defaults to 280. */
    width?: number;
}

/**
 * Left-docked Sessions panel. Files moved to a right-side drawer (see
 * {@link RightFilesDrawer}) so the chat stays the focal column and we get a
 * Cursor-style 2-or-3-column layout.
 */
export function LeftSidebar({ sessionProps, width = 280 }: LeftSidebarProps) {
    return (
        <aside
            className="border-r border-[var(--border)] bg-[var(--bg-secondary)] flex flex-col shrink-0 hidden lg:flex"
            style={{ width: `${width}px` }}
        >
            <div className="flex-1 flex flex-col min-h-0">
                <SessionSidebar {...sessionProps} embedded />
            </div>
        </aside>
    );
}
