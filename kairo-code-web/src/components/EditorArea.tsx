import { X, FileCode, Users } from 'lucide-react';
import { useOpenFilesStore, type OpenTab } from '@store/openFilesStore';
import { FileEditorPanel } from './FileEditorPanel';
import { ExpertStepTabBody } from './ExpertStepTabBody';

interface EditorAreaProps {
    workspaceId?: string;
    /** Welcome content shown when no tabs are open. */
    welcome?: React.ReactNode;
}

function tabTitle(t: OpenTab): string {
    return t.kind === 'file' ? (t.path.split('/').pop() ?? t.path) : t.title;
}

/**
 * Center editor area: VS Code-style tab bar + active tab content.
 * Hosts both file tabs (Monaco) and expert-step tabs (execution trace).
 * Falls back to a welcome slot when no tabs are open.
 */
export function EditorArea({ workspaceId, welcome }: EditorAreaProps) {
    const tabs = useOpenFilesStore((s) => s.tabs);
    const activePath = useOpenFilesStore((s) => s.activePath);
    const setActive = useOpenFilesStore((s) => s.setActive);
    const closeFile = useOpenFilesStore((s) => s.closeFile);

    if (tabs.length === 0) {
        return <div className="flex-1 min-w-0 flex flex-col">{welcome}</div>;
    }

    const active = tabs.find((t) => t.id === activePath) ?? tabs[0];

    return (
        <div className="flex-1 min-w-0 flex flex-col bg-[var(--bg-primary)]">
            {/* Tab bar */}
            <div className="flex items-stretch h-9 bg-[var(--bg-secondary)] border-b border-[var(--border)] overflow-x-auto shrink-0">
                {tabs.map((t) => {
                    const isActive = t.id === active.id;
                    const name = tabTitle(t);
                    const Icon = t.kind === 'expertStep' ? Users : FileCode;
                    return (
                        <div
                            key={t.id}
                            onClick={() => setActive(t.id)}
                            className={`group flex items-center gap-1.5 px-3 cursor-pointer border-r border-[var(--border)] text-xs whitespace-nowrap ${
                                isActive
                                    ? 'bg-[var(--bg-primary)] text-[var(--text-primary)] border-t-2 border-t-[var(--accent)]'
                                    : 'text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)] border-t-2 border-t-transparent'
                            }`}
                            title={name}
                        >
                            <Icon size={12} className={`shrink-0 ${t.kind === 'expertStep' ? 'text-violet-400' : ''}`} />
                            <span>{name}</span>
                            <button
                                onClick={(e) => {
                                    e.stopPropagation();
                                    closeFile(t.id);
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
            {/* Active tab body — keyed by id so each tab mounts cleanly. */}
            <div className="flex-1 min-h-0">
                {active.kind === 'expertStep' ? (
                    <ExpertStepTabBody key={active.id} teamId={active.teamId} stepId={active.stepId} />
                ) : (
                    <FileEditorPanel
                        key={active.id}
                        embedded
                        path={active.path}
                        gotoLine={active.gotoLine}
                        initialShowDiff={active.showDiff}
                        onClose={() => closeFile(active.id)}
                        onSaved={() => {}}
                        workspaceId={workspaceId}
                    />
                )}
            </div>
        </div>
    );
}
