import { useState, useEffect } from 'react';
import { X } from 'lucide-react';
import { getModels, createSession } from '@api/config';

interface NewSessionDialogProps {
    onClose: () => void;
    onCreate: (info: { sessionId: string; model: string }) => void;
}

type Provider = 'openai' | 'anthropic' | 'qianwen';

export function NewSessionDialog({ onClose, onCreate }: NewSessionDialogProps) {
    const [workingDir, setWorkingDir] = useState('.');
    const [provider, setProvider] = useState<Provider>('openai');
    const [model, setModel] = useState('');
    const [apiKey, setApiKey] = useState('');
    const [models, setModels] = useState<string[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [fetchingModels, setFetchingModels] = useState(true);

    useEffect(() => {
        getModels()
            .then((m) => {
                setModels(m);
                if (m.length > 0 && !model) setModel(m[0]);
            })
            .catch(() => {
                // Fallback to common models
                setModels(['gpt-4', 'gpt-3.5-turbo', 'claude-3-opus', 'claude-3-sonnet']);
            })
            .finally(() => setFetchingModels(false));
    }, []);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!workingDir.trim() || !model) return;

        setLoading(true);
        setError(null);
        try {
            const result = await createSession(workingDir.trim(), model);
            onCreate({ sessionId: result.sessionId, model });
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
                    <h2 className="text-base font-semibold text-[var(--text-primary)]">
                        New Session
                    </h2>
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
                        <input
                            type="text"
                            value={workingDir}
                            onChange={(e) => setWorkingDir(e.target.value)}
                            className="w-full px-3 py-2 text-sm rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] text-[var(--text-primary)] focus:outline-none focus:ring-2 focus:ring-[var(--color-focus-ring)]"
                            placeholder="."
                        />
                    </div>

                    <div>
                        <label className="block text-xs font-medium text-[var(--text-secondary)] mb-1">
                            Provider
                        </label>
                        <div className="flex gap-2">
                            {(['openai', 'anthropic', 'qianwen'] as Provider[]).map((p) => (
                                <button
                                    key={p}
                                    type="button"
                                    onClick={() => setProvider(p)}
                                    className={`flex-1 px-3 py-1.5 text-xs font-medium rounded-lg border transition-colors ${
                                        provider === p
                                            ? 'border-[var(--color-primary)] text-[var(--color-primary)] bg-[var(--color-primary-bg)]'
                                            : 'border-[var(--border)] text-[var(--text-secondary)] hover:bg-[var(--bg-hover)]'
                                    }`}
                                >
                                    {p}
                                </button>
                            ))}
                        </div>
                    </div>

                    <div>
                        <label className="block text-xs font-medium text-[var(--text-secondary)] mb-1">
                            Model
                        </label>
                        {fetchingModels ? (
                            <div className="text-sm text-[var(--text-muted)]">Loading models...</div>
                        ) : (
                            <select
                                value={model}
                                onChange={(e) => setModel(e.target.value)}
                                className="w-full px-3 py-2 text-sm rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] text-[var(--text-primary)] focus:outline-none focus:ring-2 focus:ring-[var(--color-focus-ring)]"
                            >
                                {models.map((m) => (
                                    <option key={m} value={m}>
                                        {m}
                                    </option>
                                ))}
                            </select>
                        )}
                    </div>

                    <div>
                        <label className="block text-xs font-medium text-[var(--text-secondary)] mb-1">
                            API Key (optional)
                        </label>
                        <input
                            type="password"
                            value={apiKey}
                            onChange={(e) => setApiKey(e.target.value)}
                            className="w-full px-3 py-2 text-sm rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] text-[var(--text-primary)] focus:outline-none focus:ring-2 focus:ring-[var(--color-focus-ring)]"
                            placeholder="Uses server default if empty"
                        />
                    </div>

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
                            disabled={loading || !workingDir.trim() || !model}
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
