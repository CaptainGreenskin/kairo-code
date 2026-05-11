import { useEffect, useRef, useState } from 'react';
import { Folder, ChevronDown, Plus, Settings as SettingsIcon } from 'lucide-react';
import { useWorkspaceStore } from '@/store/workspaceStore';

interface WorkspaceSwitcherProps {
    onCreate?: () => void;
    onOpenSettings?: (workspaceId: string) => void;
}

export function WorkspaceSwitcher({ onCreate, onOpenSettings }: WorkspaceSwitcherProps) {
    const workspaces = useWorkspaceStore((s) => s.workspaces);
    const currentId = useWorkspaceStore((s) => s.currentWorkspaceId);
    const setCurrent = useWorkspaceStore((s) => s.setCurrent);
    const current = workspaces.find((w) => w.id === currentId) ?? null;

    const [open, setOpen] = useState(false);
    const ref = useRef<HTMLDivElement>(null);

    useEffect(() => {
        if (!open) return;
        function onDocClick(e: MouseEvent) {
            if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
        }
        document.addEventListener('mousedown', onDocClick);
        return () => document.removeEventListener('mousedown', onDocClick);
    }, [open]);

    if (!current && workspaces.length === 0) {
        return (
            <button
                onClick={onCreate}
                className="flex items-center gap-1.5 px-2 py-1 text-xs rounded border border-dashed border-[var(--border)] text-[var(--text-secondary)] hover:bg-[var(--bg-hover)]"
                title="Create workspace"
            >
                <Plus size={12} /> New workspace
            </button>
        );
    }

    const label = current?.name ?? 'Select workspace';

    return (
        <div className="relative" ref={ref}>
            <button
                onClick={() => setOpen((v) => !v)}
                className="flex items-center gap-1.5 px-2 py-1 text-xs rounded hover:bg-[var(--bg-hover)] text-[var(--text-secondary)]"
                title={current?.workingDir ?? 'Select workspace'}
            >
                <Folder size={12} />
                <span className="font-medium text-[var(--text-primary)] max-w-[140px] truncate">
                    {label}
                </span>
                <ChevronDown size={12} />
            </button>

            {open && (
                <div className="absolute top-full left-0 mt-1 w-72 bg-[var(--bg-secondary)] border border-[var(--border)] rounded shadow-lg z-50 overflow-hidden">
                    <div className="max-h-72 overflow-auto">
                        {workspaces.map((w) => (
                            <button
                                key={w.id}
                                onClick={() => {
                                    setCurrent(w.id);
                                    setOpen(false);
                                }}
                                className={`w-full text-left px-3 py-2 text-xs flex items-start gap-2 hover:bg-[var(--bg-hover)] ${
                                    w.id === currentId ? 'bg-[var(--bg-hover)]' : ''
                                }`}
                            >
                                <Folder size={12} className="mt-0.5 shrink-0" />
                                <div className="flex-1 min-w-0">
                                    <div className="font-medium text-[var(--text-primary)] truncate">
                                        {w.name}
                                    </div>
                                    <div className="text-[10px] text-[var(--text-muted)] truncate">
                                        {w.workingDir}
                                        {w.useWorktree && (
                                            <span className="ml-1 text-amber-500">· worktree</span>
                                        )}
                                    </div>
                                </div>
                                {onOpenSettings && w.id === currentId && (
                                    <button
                                        onClick={(e) => {
                                            e.stopPropagation();
                                            onOpenSettings(w.id);
                                            setOpen(false);
                                        }}
                                        className="p-1 -mr-1 text-[var(--text-secondary)] hover:text-[var(--text-primary)]"
                                        aria-label="Workspace settings"
                                        title="Workspace settings"
                                    >
                                        <SettingsIcon size={12} />
                                    </button>
                                )}
                            </button>
                        ))}
                    </div>
                    {onCreate && (
                        <button
                            onClick={() => {
                                onCreate();
                                setOpen(false);
                            }}
                            className="w-full text-left px-3 py-2 text-xs flex items-center gap-2 border-t border-[var(--border)] hover:bg-[var(--bg-hover)] text-[var(--text-secondary)]"
                        >
                            <Plus size={12} /> New workspace…
                        </button>
                    )}
                </div>
            )}
        </div>
    );
}
