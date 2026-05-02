import { useState, useEffect, useCallback } from 'react';
import { X, GitBranch, RefreshCw, Plus, Minus, ChevronRight, ChevronDown } from 'lucide-react';

interface GitFile {
    status: string;
    path: string;
}

interface GitStatusPanelProps {
    onClose: () => void;
}

const STATUS_LABELS: Record<string, { label: string; color: string }> = {
    M: { label: 'M', color: 'text-amber-400' },
    A: { label: 'A', color: 'text-emerald-400' },
    D: { label: 'D', color: 'text-red-400' },
    '??': { label: '?', color: 'text-[var(--text-muted)]' },
};

function getStatusStyle(status: string) {
    return STATUS_LABELS[status] ?? { label: status, color: 'text-[var(--text-muted)]' };
}

function DiffLine({ line }: { line: string }) {
    if (line.startsWith('+++') || line.startsWith('---')) {
        return <div className="text-[var(--text-muted)] text-[10px] font-mono px-2 py-0.5">{line}</div>;
    }
    if (line.startsWith('+')) {
        return (
            <div className="bg-emerald-500/10 text-emerald-400 text-[10px] font-mono px-2 py-0.5 flex items-start gap-1">
                <Plus size={9} className="mt-0.5 shrink-0" />
                <span className="whitespace-pre-wrap break-all">{line.slice(1)}</span>
            </div>
        );
    }
    if (line.startsWith('-')) {
        return (
            <div className="bg-red-500/10 text-red-400 text-[10px] font-mono px-2 py-0.5 flex items-start gap-1">
                <Minus size={9} className="mt-0.5 shrink-0" />
                <span className="whitespace-pre-wrap break-all">{line.slice(1)}</span>
            </div>
        );
    }
    if (line.startsWith('@@')) {
        return <div className="bg-blue-500/10 text-blue-400 text-[10px] font-mono px-2 py-1">{line}</div>;
    }
    return <div className="text-[var(--text-secondary)] text-[10px] font-mono px-2 py-0.5 whitespace-pre-wrap break-all">{line}</div>;
}

function FileDiff({ path }: { path: string }) {
    const [diff, setDiff] = useState<string | null>(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        fetch(`/api/git/diff?path=${encodeURIComponent(path)}`)
            .then(async (res) => {
                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                const data = (await res.json()) as { diff: string };
                if (!cancelled) setDiff(data.diff || '(no changes)');
            })
            .catch(() => {
                if (!cancelled) setDiff('(failed to load diff)');
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, [path]);

    if (loading) {
        return (
            <div className="px-3 py-2 text-[10px] text-[var(--text-muted)] border-t border-[var(--border)]">
                Loading diff…
            </div>
        );
    }
    if (!diff) return null;
    return (
        <div className="border-t border-[var(--border)] overflow-x-auto max-h-64 overflow-y-auto bg-[var(--bg-primary)]">
            {diff.split('\n').map((line, i) => (
                <DiffLine key={i} line={line} />
            ))}
        </div>
    );
}

export function GitStatusPanel({ onClose }: GitStatusPanelProps) {
    const [files, setFiles] = useState<GitFile[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [expandedFile, setExpandedFile] = useState<string | null>(null);

    const refresh = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            const res = await fetch('/api/git/status');
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const data = (await res.json()) as GitFile[];
            setFiles(data);
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed to load git status');
        }
        setLoading(false);
    }, []);

    useEffect(() => {
        refresh();
    }, [refresh]);

    return (
        <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
            onClick={(e) => {
                if (e.target === e.currentTarget) onClose();
            }}
        >
            <div
                className="relative flex flex-col w-full max-w-2xl h-[75vh] max-h-[750px]
                bg-[var(--bg-primary)] border border-[var(--border)] rounded-xl shadow-2xl overflow-hidden"
            >
                <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--border)] bg-[var(--bg-secondary)] shrink-0">
                    <div className="flex items-center gap-2">
                        <GitBranch size={15} className="text-[var(--accent)]" />
                        <span className="text-sm font-semibold text-[var(--text-primary)]">Git Changes</span>
                        {files.length > 0 && (
                            <span className="text-xs text-[var(--text-muted)]">
                                {files.length} file{files.length !== 1 ? 's' : ''}
                            </span>
                        )}
                    </div>
                    <div className="flex items-center gap-1.5">
                        <button
                            onClick={refresh}
                            disabled={loading}
                            className="p-1.5 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                            title="Refresh"
                            aria-label="Refresh git status"
                        >
                            <RefreshCw size={13} className={loading ? 'animate-spin' : ''} />
                        </button>
                        <button
                            onClick={onClose}
                            className="p-1.5 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                            aria-label="Close"
                        >
                            <X size={16} />
                        </button>
                    </div>
                </div>

                <div className="flex-1 overflow-y-auto">
                    {error ? (
                        <div className="flex flex-col items-center justify-center h-full text-sm px-4 text-center gap-2">
                            <span className={error.includes('503')
                                ? 'text-[var(--text-muted)]'
                                : 'text-red-400'}>
                                {error.includes('503')
                                    ? 'Configure working directory in Settings'
                                    : error.includes('500') || error.includes('128') || error.toLowerCase().includes('not a git')
                                        ? 'Not a git repository or git not available'
                                        : error}
                            </span>
                        </div>
                    ) : loading && files.length === 0 ? (
                        <div className="flex items-center justify-center h-full text-sm text-[var(--text-muted)]">
                            Loading…
                        </div>
                    ) : files.length === 0 ? (
                        <div className="flex flex-col items-center justify-center h-full text-sm text-[var(--text-muted)] gap-2">
                            <GitBranch size={32} className="opacity-30" />
                            <span>Working tree is clean</span>
                        </div>
                    ) : (
                        <div>
                            {files.map((file) => {
                                const s = getStatusStyle(file.status);
                                const isExpanded = expandedFile === file.path;
                                return (
                                    <div key={file.path} className="border-b border-[var(--border)] last:border-0">
                                        <button
                                            onClick={() => setExpandedFile(isExpanded ? null : file.path)}
                                            className="w-full flex items-center gap-2 px-4 py-2.5 hover:bg-[var(--bg-hover)] transition-colors text-left"
                                        >
                                            <span className={`text-xs font-mono font-bold w-4 shrink-0 ${s.color}`}>
                                                {s.label}
                                            </span>
                                            <span className="text-xs text-[var(--text-primary)] font-mono truncate flex-1">
                                                {file.path}
                                            </span>
                                            {isExpanded ? (
                                                <ChevronDown size={12} className="text-[var(--text-muted)] shrink-0" />
                                            ) : (
                                                <ChevronRight size={12} className="text-[var(--text-muted)] shrink-0" />
                                            )}
                                        </button>
                                        {isExpanded && file.status !== '??' && <FileDiff path={file.path} />}
                                        {isExpanded && file.status === '??' && (
                                            <div className="px-4 py-2 text-[10px] text-[var(--text-muted)] border-t border-[var(--border)]">
                                                Untracked file — no diff available
                                            </div>
                                        )}
                                    </div>
                                );
                            })}
                        </div>
                    )}
                </div>

                <div className="px-4 py-2 border-t border-[var(--border)] bg-[var(--bg-secondary)] shrink-0 text-[10px] text-[var(--text-muted)]">
                    M modified · A added · D deleted · ? untracked · click file to expand diff
                </div>
            </div>
        </div>
    );
}
