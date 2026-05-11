import { type ComponentProps } from 'react';
import { Folder, X } from 'lucide-react';
import { FileTreePanel } from './FileTreePanel';

type FileTreePanelProps = Omit<ComponentProps<typeof FileTreePanel>, 'embedded' | 'isOpen' | 'onToggle'>;

interface RightFilesDrawerProps extends FileTreePanelProps {
    isOpen: boolean;
    onClose: () => void;
    width?: number;
}

/**
 * Right-side docked Files drawer — Cursor / Linear / Sentry style. When open,
 * the chat narrows and the file tree occupies a fixed-width column on the
 * right. When closed, the chat reclaims the full width. Toggled from the
 * header's FolderTree icon.
 */
export function RightFilesDrawer({
    isOpen,
    onClose,
    width = 320,
    ...fileTreeProps
}: RightFilesDrawerProps) {
    if (!isOpen) return null;
    return (
        <aside
            className="border-l border-[var(--border)] bg-[var(--bg-secondary)] flex flex-col shrink-0 hidden lg:flex"
            style={{ width: `${width}px` }}
        >
            <div className="flex items-center justify-between h-9 px-3 border-b border-[var(--border)] shrink-0">
                <div className="flex items-center gap-1.5 text-[11px] uppercase tracking-wider text-[var(--text-secondary)]">
                    <Folder size={13} />
                    <span>Files</span>
                </div>
                <button
                    onClick={onClose}
                    className="p-0.5 rounded hover:bg-[var(--bg-primary)]/60 text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                    aria-label="Close files panel"
                    title="Close (toggle from header icon)"
                >
                    <X size={14} />
                </button>
            </div>
            <div className="flex-1 flex flex-col min-h-0">
                <FileTreePanel {...fileTreeProps} isOpen onToggle={onClose} embedded />
            </div>
        </aside>
    );
}
