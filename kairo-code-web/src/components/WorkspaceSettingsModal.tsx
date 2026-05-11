import { useEffect, useRef, useState } from 'react';
import { X, Folder, Check, Trash2 } from 'lucide-react';
import { useWorkspaceStore } from '@/store/workspaceStore';
import type { Workspace } from '@/utils/workspaceApi';
import { DirPicker } from './DirPicker';

interface WorkspaceSettingsModalProps {
    isOpen: boolean;
    onClose: () => void;
    /** When provided, edits the workspace with this id; when null, creates a new one. */
    workspaceId: string | null;
}

export function WorkspaceSettingsModal({ isOpen, onClose, workspaceId }: WorkspaceSettingsModalProps) {
    const workspaces = useWorkspaceStore((s) => s.workspaces);
    const create = useWorkspaceStore((s) => s.create);
    const update = useWorkspaceStore((s) => s.update);
    const remove = useWorkspaceStore((s) => s.remove);

    const editing: Workspace | null = workspaceId
        ? workspaces.find((w) => w.id === workspaceId) ?? null
        : null;

    const [name, setName] = useState('');
    const [workingDir, setWorkingDir] = useState('');
    const [useWorktree, setUseWorktree] = useState(false);
    const [showDirPicker, setShowDirPicker] = useState(false);
    const [saving, setSaving] = useState(false);
    const [saved, setSaved] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const overlayRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        if (!isOpen) return;
        if (editing) {
            setName(editing.name);
            setWorkingDir(editing.workingDir);
            setUseWorktree(editing.useWorktree);
        } else {
            setName('');
            setWorkingDir('');
            setUseWorktree(false);
        }
        setError(null);
        setSaved(false);
    }, [isOpen, editing?.id]);

    useEffect(() => {
        if (!isOpen) return;
        const handleEsc = (e: KeyboardEvent) => {
            if (e.key === 'Escape') onClose();
        };
        window.addEventListener('keydown', handleEsc);
        return () => window.removeEventListener('keydown', handleEsc);
    }, [isOpen, onClose]);

    if (!isOpen) return null;

    const handleSave = async () => {
        if (!name.trim() || !workingDir.trim()) {
            setError('Name and working directory are required.');
            return;
        }
        setSaving(true);
        setError(null);
        try {
            if (editing) {
                await update(editing.id, {
                    name: name.trim(),
                    workingDir: workingDir.trim(),
                    useWorktree,
                });
            } else {
                await create({
                    name: name.trim(),
                    workingDir: workingDir.trim(),
                    useWorktree,
                });
            }
            setSaved(true);
            setTimeout(() => {
                setSaved(false);
                onClose();
            }, 700);
        } catch (e) {
            setError(e instanceof Error ? e.message : String(e));
        } finally {
            setSaving(false);
        }
    };

    const handleDelete = async () => {
        if (!editing) return;
        if (!window.confirm(`Delete workspace "${editing.name}"? Sessions in it must be closed first.`))
            return;
        setSaving(true);
        setError(null);
        try {
            await remove(editing.id);
            onClose();
        } catch (e) {
            setError(e instanceof Error ? e.message : String(e));
        } finally {
            setSaving(false);
        }
    };

    const handleOverlayClick = (e: React.MouseEvent) => {
        if (e.target === overlayRef.current) onClose();
    };

    const inputClass =
        'w-full px-3 py-2 text-sm rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] text-[var(--text-primary)] focus:outline-none focus:ring-2 focus:ring-[var(--color-focus-ring)]';
    const labelClass = 'block text-xs font-medium text-[var(--text-secondary)] mb-1';

    return (
        <div
            ref={overlayRef}
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
            onClick={handleOverlayClick}
        >
            <div className="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl shadow-xl w-full max-w-md mx-4">
                <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--border)]">
                    <h2 className="text-sm font-semibold text-[var(--text-primary)]">
                        {editing ? `Edit workspace · ${editing.name}` : 'New workspace'}
                    </h2>
                    <button
                        onClick={onClose}
                        className="text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
                        aria-label="Close"
                    >
                        <X size={18} />
                    </button>
                </div>

                <div className="px-4 py-4 space-y-4">
                    {error && (
                        <div className="text-sm text-[var(--color-danger)] bg-[var(--color-danger-bg)] rounded-lg px-3 py-2">
                            {error}
                        </div>
                    )}

                    <div>
                        <label className={labelClass}>Name</label>
                        <input
                            className={inputClass}
                            placeholder="my-project"
                            value={name}
                            onChange={(e) => setName(e.target.value)}
                            autoFocus
                        />
                    </div>

                    <div>
                        <label className={labelClass}>Working Directory</label>
                        <div className="flex gap-1">
                            <input
                                className={`${inputClass} flex-1 font-mono`}
                                placeholder="~/repo"
                                value={workingDir}
                                onChange={(e) => setWorkingDir(e.target.value)}
                            />
                            <button
                                type="button"
                                onClick={() => setShowDirPicker((v) => !v)}
                                className={`px-2.5 py-2 rounded-lg border transition-colors ${showDirPicker ? 'border-[var(--color-primary)] text-[var(--color-primary)] bg-[var(--color-primary-bg)]' : 'border-[var(--border)] text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)]'}`}
                                title="Browse directories"
                            >
                                <Folder size={15} />
                            </button>
                        </div>
                        {showDirPicker && (
                            <DirPicker
                                currentPath={workingDir}
                                onSelect={(p) => setWorkingDir(p)}
                                onClose={() => setShowDirPicker(false)}
                            />
                        )}
                    </div>

                    <label className="flex items-start gap-2 text-xs text-[var(--text-primary)] cursor-pointer">
                        <input
                            type="checkbox"
                            checked={useWorktree}
                            onChange={(e) => setUseWorktree(e.target.checked)}
                            className="mt-0.5"
                        />
                        <span>
                            <span className="font-medium">Use git worktree per session</span>
                            <span className="block text-[var(--text-muted)] mt-0.5">
                                Each new session gets its own git worktree on a {`kairo/<sid>`} branch.
                                Falls back to the working dir for non-git folders.
                            </span>
                        </span>
                    </label>

                    <div className="flex justify-between pt-2">
                        {editing ? (
                            <button
                                className="px-3 py-2 text-xs text-[var(--color-danger)] hover:bg-[var(--color-danger-bg)] rounded transition-colors flex items-center gap-1"
                                onClick={handleDelete}
                                disabled={saving}
                            >
                                <Trash2 size={14} /> Delete
                            </button>
                        ) : (
                            <span />
                        )}
                        <div className="flex gap-2">
                            <button
                                className="px-4 py-2 text-sm text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
                                onClick={onClose}
                                disabled={saving}
                            >
                                Cancel
                            </button>
                            <button
                                className="px-4 py-2 text-sm bg-[var(--color-primary)] hover:bg-[var(--color-primary-hover)] text-white rounded-lg transition-colors disabled:opacity-50"
                                onClick={handleSave}
                                disabled={saving}
                            >
                                {saved ? (
                                    <span className="flex items-center gap-1">
                                        <Check size={14} /> Saved
                                    </span>
                                ) : saving ? (
                                    'Saving…'
                                ) : editing ? (
                                    'Save'
                                ) : (
                                    'Create'
                                )}
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
}
