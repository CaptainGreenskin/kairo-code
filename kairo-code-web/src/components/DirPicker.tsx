import { useState, useEffect, useCallback } from 'react';
import { Folder, ChevronRight, ChevronLeft, Home } from 'lucide-react';
import { getDirs, type DirEntry } from '@api/config';

interface DirPickerProps {
    currentPath: string;
    onSelect: (path: string) => void;
    onClose: () => void;
}

export function DirPicker({ currentPath, onSelect, onClose }: DirPickerProps) {
    const [browsePath, setBrowsePath] = useState('');
    const [dirs, setDirs] = useState<DirEntry[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const loadDirs = useCallback((path: string) => {
        setLoading(true);
        setError(null);
        getDirs(path)
            .then((entries) => {
                setDirs(entries);
                setBrowsePath(path || '~');
            })
            .catch(() => setError('Cannot read directory'))
            .finally(() => setLoading(false));
    }, []);

    useEffect(() => {
        const tryPath = (path: string, fallbacks: string[]) => {
            setLoading(true);
            setError(null);
            getDirs(path)
                .then((entries) => {
                    setDirs(entries);
                    setBrowsePath(path || '~');
                    setLoading(false);
                })
                .catch(() => {
                    if (fallbacks.length > 0) {
                        tryPath(fallbacks[0], fallbacks.slice(1));
                    } else {
                        setError('Cannot read directory');
                        setLoading(false);
                    }
                });
        };

        const fallbacks: string[] = [];
        if (currentPath && currentPath !== '.' && currentPath !== '/') {
            const parts = currentPath.replace(/\/$/, '').split('/');
            if (parts.length > 1) {
                parts.pop();
                fallbacks.push(parts.join('/') || '/');
            }
        }
        fallbacks.push('');

        tryPath(currentPath || '', fallbacks);
    }, []);

    const goUp = () => {
        const parts = browsePath.replace(/\/$/, '').split('/');
        if (parts.length <= 1) return;
        parts.pop();
        const parent = parts.join('/') || '/';
        loadDirs(parent);
    };

    return (
        <div className="mt-1 border border-[var(--border)] rounded-lg bg-[var(--bg-primary)] overflow-hidden">
            {/* Path bar */}
            <div className="flex items-center gap-1 px-2 py-1.5 border-b border-[var(--border)] bg-[var(--bg-secondary)]">
                <button
                    type="button"
                    onClick={() => loadDirs('')}
                    className="p-1 text-[var(--text-muted)] hover:text-[var(--text-primary)] rounded"
                    title="Home"
                >
                    <Home size={12} />
                </button>
                <button
                    type="button"
                    onClick={goUp}
                    className="p-1 text-[var(--text-muted)] hover:text-[var(--text-primary)] rounded"
                    title="Parent directory"
                >
                    <ChevronLeft size={12} />
                </button>
                <span className="flex-1 text-xs text-[var(--text-muted)] truncate font-mono" title={browsePath}>{browsePath}</span>
                <button
                    type="button"
                    onClick={() => { onSelect(browsePath); onClose(); }}
                    className="px-2 py-0.5 text-xs font-medium text-white bg-[var(--color-primary)] rounded hover:bg-[var(--color-primary-hover)] whitespace-nowrap"
                    title="Select current directory"
                >
                    Use this
                </button>
            </div>
            {/* Dir list — click name to SELECT, click → to navigate in */}
            <div className="max-h-48 overflow-y-auto">
                {loading && (
                    <div className="px-3 py-2 text-xs text-[var(--text-muted)]">Loading…</div>
                )}
                {error && (
                    <div className="px-3 py-2 text-xs text-[var(--color-danger)]">{error}</div>
                )}
                {!loading && !error && dirs.length === 0 && (
                    <div className="px-3 py-2 text-xs text-[var(--text-muted)]">No subdirectories</div>
                )}
                {!loading && dirs.map((d) => (
                    <div
                        key={d.path}
                        className="flex items-center gap-2 px-3 py-1.5 text-xs text-[var(--text-primary)] hover:bg-[var(--bg-hover)] group"
                    >
                        <button
                            type="button"
                            onClick={() => { onSelect(d.path); onClose(); }}
                            className="flex items-center gap-2 flex-1 min-w-0 text-left"
                            title={`Select ${d.path}`}
                        >
                            <Folder size={12} className="text-[var(--color-primary)] shrink-0" />
                            <span className="truncate">{d.name}</span>
                        </button>
                        <button
                            type="button"
                            onClick={() => loadDirs(d.path)}
                            className="p-0.5 text-[var(--text-muted)] hover:text-[var(--text-primary)] opacity-0 group-hover:opacity-100 transition-opacity shrink-0"
                            title={`Open ${d.name}`}
                        >
                            <ChevronRight size={12} />
                        </button>
                    </div>
                ))}
            </div>
            <div className="px-3 py-1.5 border-t border-[var(--border)] bg-[var(--bg-secondary)]">
                <p className="text-[10px] text-[var(--text-muted)]">
                    Click folder to select · Click <ChevronRight size={9} className="inline" /> to browse inside
                </p>
            </div>
        </div>
    );
}
