import { useState } from 'react';
import { X, Folder } from 'lucide-react';
import { DirPicker } from './DirPicker';

interface NewSessionDialogProps {
    onClose: () => void;
    onCreate: (info: { sessionId: string; model: string }) => void;
    onCreateSession: (workingDir: string) => Promise<{ sessionId: string }>;
    defaultWorkingDir?: string;
}

const LAST_DIR_KEY = 'kairo-last-working-dir';

export function NewSessionDialog({ onClose, onCreate, onCreateSession, defaultWorkingDir }: NewSessionDialogProps) {
    const [workingDir, setWorkingDir] = useState(() => {
        // Priority: defaultWorkingDir (from server config) → last used dir → '.'
        return defaultWorkingDir
            ?? localStorage.getItem(LAST_DIR_KEY)
            ?? '.';
    });
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [showDirPicker, setShowDirPicker] = useState(false);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        const dir = workingDir.trim();
        if (!dir) return;

        setLoading(true);
        setError(null);
        try {
            localStorage.setItem(LAST_DIR_KEY, dir);
            const result = await onCreateSession(dir);
            onCreate({ sessionId: result.sessionId, model: '' });
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed to create session');
            setLoading(false);
        }
    };

    return (
        <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
            onClick={(e) => {
                if (e.target === e.currentTarget) onClose();
            }}
        >
            <div className="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl shadow-xl w-full max-w-md mx-4 overflow-hidden">
                <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--border)]">
                    <h2 className="text-base font-semibold text-[var(--text-primary)]">New Session</h2>
                    <button
                        onClick={onClose}
                        className="p-1 text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                        aria-label="Close"
                    >
                        <X size={18} />
                    </button>
                </div>

                <form onSubmit={handleSubmit} className="p-4 space-y-4">
                    {error && (
                        <div className="px-3 py-2 text-sm text-[var(--color-danger)] bg-[var(--color-danger-bg)] rounded-lg">
                            {error}
                        </div>
                    )}

                    <div>
                        <label className="block text-xs font-medium text-[var(--text-secondary)] mb-1">
                            Working Directory
                        </label>
                        <div className="flex gap-1">
                            <input
                                type="text"
                                value={workingDir}
                                onChange={(e) => setWorkingDir(e.target.value)}
                                className="flex-1 px-3 py-2 text-sm rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] text-[var(--text-primary)] focus:outline-none focus:ring-2 focus:ring-[var(--color-focus-ring)] font-mono"
                                placeholder="/path/to/project"
                                autoFocus
                            />
                            <button
                                type="button"
                                onClick={() => setShowDirPicker(v => !v)}
                                className={`px-2.5 py-2 rounded-lg border transition-colors ${showDirPicker ? 'border-[var(--color-primary)] text-[var(--color-primary)] bg-[var(--color-primary-bg)]' : 'border-[var(--border)] text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)]'}`}
                                title="Browse directories"
                            >
                                <Folder size={15} />
                            </button>
                        </div>
                        {showDirPicker && (
                            <DirPicker
                                currentPath={workingDir}
                                onSelect={(path) => { setWorkingDir(path); }}
                                onClose={() => setShowDirPicker(false)}
                            />
                        )}
                    </div>

                    <p className="text-xs text-[var(--text-muted)]">
                        Model, provider, and API key are taken from Settings.
                    </p>

                    <div className="flex justify-end gap-2 pt-2">
                        <button
                            type="button"
                            onClick={onClose}
                            className="px-4 py-2 text-sm font-medium text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
                        >
                            Cancel
                        </button>
                        <button
                            type="submit"
                            disabled={loading || !workingDir.trim()}
                            className="px-4 py-2 text-sm font-medium text-white bg-[var(--color-primary)] hover:bg-[var(--color-primary-hover)] rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            {loading ? 'Creating...' : 'Create'}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
}
