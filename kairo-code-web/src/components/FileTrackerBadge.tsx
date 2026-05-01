import { useState, useRef, useEffect } from 'react';
import { Files, FileEdit, FileSearch, Eye, X } from 'lucide-react';
import type { TrackedFile, FileOp } from '@hooks/useFileTracker';

interface FileTrackerBadgeProps {
    files: TrackedFile[];
    onClear: () => void;
}

export function FileTrackerBadge({ files, onClear }: FileTrackerBadgeProps) {
    const [open, setOpen] = useState(false);
    const ref = useRef<HTMLDivElement>(null);

    useEffect(() => {
        if (!open) return;
        const handler = (e: MouseEvent) => {
            if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
        };
        document.addEventListener('mousedown', handler);
        return () => document.removeEventListener('mousedown', handler);
    }, [open]);

    if (files.length === 0) return null;

    const writeCount = files.filter(f => f.ops.has('write')).length;

    const opIcon = (ops: Set<FileOp>) => {
        if (ops.has('write')) return <FileEdit size={11} className="text-amber-400" />;
        if (ops.has('read')) return <Eye size={11} className="text-blue-400" />;
        return <FileSearch size={11} className="text-[var(--text-muted)]" />;
    };

    const shortPath = (p: string) => {
        const parts = p.split('/');
        return parts.length > 3 ? '…/' + parts.slice(-2).join('/') : p;
    };

    return (
        <div ref={ref} className="relative">
            <button
                onClick={() => setOpen(o => !o)}
                className="flex items-center gap-1 px-2 py-1 rounded-lg bg-[var(--bg-hover)] hover:bg-[var(--border)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors text-xs"
                title="Files touched this session"
            >
                <Files size={12} />
                <span>{files.length}</span>
                {writeCount > 0 && (
                    <span className="flex items-center gap-0.5 text-amber-400">
                        <FileEdit size={10} />{writeCount}
                    </span>
                )}
            </button>

            {open && (
                <div className="absolute top-full right-0 mt-1 z-40 w-80 bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl shadow-xl overflow-hidden">
                    <div className="flex items-center justify-between px-3 py-2 border-b border-[var(--border)] bg-[var(--bg-primary)]">
                        <span className="text-xs font-semibold text-[var(--text-primary)]">Files this session</span>
                        <button
                            onClick={() => { onClear(); setOpen(false); }}
                            className="text-xs text-[var(--text-muted)] hover:text-red-400 transition-colors flex items-center gap-1"
                        >
                            <X size={11} /> Clear
                        </button>
                    </div>
                    <div className="max-h-64 overflow-y-auto divide-y divide-[var(--border)]">
                        {files.map(f => (
                            <div key={f.path} className="flex items-center gap-2 px-3 py-1.5 hover:bg-[var(--bg-hover)]">
                                {opIcon(f.ops)}
                                <span className="text-xs font-mono text-[var(--text-primary)] truncate flex-1" title={f.path}>
                                    {shortPath(f.path)}
                                </span>
                                <div className="flex gap-0.5">
                                    {f.ops.has('write') && <span className="text-xs text-amber-400 bg-amber-400/10 px-1 rounded">write</span>}
                                    {f.ops.has('read') && !f.ops.has('write') && <span className="text-xs text-blue-400 bg-blue-400/10 px-1 rounded">read</span>}
                                    {f.ops.has('search') && <span className="text-xs text-[var(--text-muted)] bg-[var(--bg-hover)] px-1 rounded">search</span>}
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
            )}
        </div>
    );
}
