import { Folder, Plus, Settings as SettingsIcon } from 'lucide-react';
import { useWorkspaceStore } from '@store/workspaceStore';

interface WorkspacesViewProps {
    onCreate: () => void;
    onOpenSettings: (workspaceId: string) => void;
}

export function WorkspacesView({ onCreate, onOpenSettings }: WorkspacesViewProps) {
    const workspaces = useWorkspaceStore((s) => s.workspaces);
    const currentId = useWorkspaceStore((s) => s.currentWorkspaceId);
    const setCurrent = useWorkspaceStore((s) => s.setCurrent);

    return (
        <div className="flex flex-col h-full overflow-hidden">
            <div className="px-3 py-2 border-b border-[var(--border)]">
                <button
                    onClick={onCreate}
                    className="w-full flex items-center justify-center gap-1.5 px-2 py-1.5 text-xs rounded border border-dashed border-[var(--border)] text-[var(--text-secondary)] hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)]"
                >
                    <Plus size={12} /> New Workspace
                </button>
            </div>

            <div className="flex-1 overflow-y-auto">
                {workspaces.length === 0 ? (
                    <div className="px-3 py-6 text-center text-xs text-[var(--text-muted)]">
                        No workspaces yet.
                    </div>
                ) : (
                    workspaces.map((w) => {
                        const active = w.id === currentId;
                        return (
                            <div
                                key={w.id}
                                className={`group flex items-center gap-2 px-3 py-2 cursor-pointer border-l-2 ${
                                    active
                                        ? 'border-[var(--accent)] bg-[var(--bg-hover)]'
                                        : 'border-transparent hover:bg-[var(--bg-hover)]'
                                }`}
                                onClick={() => setCurrent(w.id)}
                                title={w.workingDir}
                            >
                                <Folder size={14} className="shrink-0 text-[var(--text-muted)]" />
                                <div className="flex-1 min-w-0">
                                    <div className="text-sm text-[var(--text-primary)] truncate">
                                        {w.name}
                                    </div>
                                    <div className="text-[10px] text-[var(--text-muted)] truncate font-mono">
                                        {w.workingDir}
                                    </div>
                                </div>
                                <button
                                    className="shrink-0 opacity-0 group-hover:opacity-100 p-1 text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-opacity"
                                    onClick={(e) => {
                                        e.stopPropagation();
                                        onOpenSettings(w.id);
                                    }}
                                    title="Workspace settings"
                                    aria-label="Workspace settings"
                                >
                                    <SettingsIcon size={12} />
                                </button>
                            </div>
                        );
                    })
                )}
            </div>
        </div>
    );
}
