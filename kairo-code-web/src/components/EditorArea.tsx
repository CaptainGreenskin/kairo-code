import { X, FileCode } from 'lucide-react';
import { useOpenFilesStore } from '@store/openFilesStore';
import { FileEditorPanel } from './FileEditorPanel';

interface EditorAreaProps {
    workspaceId?: string;
    /** Welcome content shown when no tabs are open. */
    welcome?: React.ReactNode;
}

/**
 * Center editor area: VS Code-style tab bar + active tab content.
 * Falls back to a welcome slot when no files are open.
 */
export function EditorArea({ workspaceId, welcome }: EditorAreaProps) {
    const tabs = useOpenFilesStore((s) => s.tabs);
    const activePath = useOpenFilesStore((s) => s.activePath);
    const setActive = useOpenFilesStore((s) => s.setActive);
    const closeFile = useOpenFilesStore((s) => s.closeFile);

    if (tabs.length === 0) {
        return <div className="flex-1 min-w-0 flex flex-col">{welcome}</div>;
    }

    const active = tabs.find((t) => t.path === activePath) ?? tabs[0];

    return (
        <div className="flex-1 min-w-0 flex flex-col bg-[var(--bg-primary)]">
            {/* Tab bar */}
            <div className="flex items-stretch h-9 bg-[var(--bg-secondary)] border-b border-[var(--border)] overflow-x-auto shrink-0">
                {tabs.map((t) => {
                    const isActive = t.path === active.path;
                    const name = t.path.split('/').pop() ?? t.path;
                    return (
                        <div
                            key={t.path}
                            onClick={() => setActive(t.path)}
                            className={`group flex items-center gap-1.5 px-3 cursor-pointer border-r border-[var(--border)] text-xs whitespace-nowrap ${
                                isActive
                                    ? 'bg-[var(--bg-primary)] text-[var(--text-primary)] border-t-2 border-t-[var(--accent)]'
                                    : 'text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)] border-t-2 border-t-transparent'
                            }`}
                            title={t.path}
                        >
                            <FileCode size={12} className="shrink-0" />
                            <span>{name}</span>
                            <button
                                onClick={(e) => {
                                    e.stopPropagation();
                                    closeFile(t.path);
                                }}
                                className="ml-1 p-0.5 rounded opacity-50 hover:opacity-100 hover:bg-[var(--bg-hover)]"
                                aria-label={`Close ${name}`}
                            >
                                <X size={11} />
                            </button>
                        </div>
                    );
                })}
            </div>
            {/* Active tab body — keyed by path so Monaco re-mounts cleanly per file. */}
            <div className="flex-1 min-h-0">
                <FileEditorPanel
                    key={active.path}
                    embedded
                    path={active.path}
                    gotoLine={active.gotoLine}
                    onClose={() => closeFile(active.path)}
                    onSaved={() => {}}
                    workspaceId={workspaceId}
                />
            </div>
        </div>
    );
}
