import { useEffect, useRef } from 'react';
import { X } from 'lucide-react';

interface SearchBarProps {
    isOpen: boolean;
    query: string;
    onQueryChange: (q: string) => void;
    resultCount: number;
    onClose: () => void;
}

export function SearchBar({ isOpen, query, onQueryChange, resultCount, onClose }: SearchBarProps) {
    const inputRef = useRef<HTMLInputElement>(null);

    useEffect(() => {
        if (isOpen) {
            inputRef.current?.focus();
            inputRef.current?.select();
        }
    }, [isOpen]);

    if (!isOpen) return null;

    return (
        <div className="flex items-center gap-2 px-3 py-2 border-b border-[var(--border)] bg-[var(--bg-secondary)]">
            <input
                ref={inputRef}
                type="text"
                value={query}
                onChange={e => onQueryChange(e.target.value)}
                onKeyDown={e => {
                    if (e.key === 'Escape') onClose();
                }}
                placeholder="Search messages…"
                className="flex-1 bg-transparent text-sm text-[var(--text-primary)] outline-none placeholder:text-[var(--text-muted)]"
            />
            {query.length > 0 && (
                <span className="text-xs text-[var(--text-secondary)]">
                    {resultCount} result{resultCount !== 1 ? 's' : ''}
                </span>
            )}
            <button
                onClick={onClose}
                className="p-0.5 text-[var(--text-secondary)] hover:text-[var(--text-primary)]"
            >
                <X size={14} />
            </button>
        </div>
    );
}
