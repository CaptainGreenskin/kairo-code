import { useEffect, useRef, useState } from 'react';
import { Search, X, ChevronUp, ChevronDown } from 'lucide-react';

interface MessageSearchBarProps {
    onQueryChange: (query: string) => void;
    onClose: () => void;
    matchCount: number;
    currentMatch: number;
    onPrev: () => void;
    onNext: () => void;
}

export function MessageSearchBar({ onQueryChange, onClose, matchCount, currentMatch, onPrev, onNext }: MessageSearchBarProps) {
    const [value, setValue] = useState('');
    const inputRef = useRef<HTMLInputElement>(null);

    useEffect(() => {
        inputRef.current?.focus();
    }, []);

    const handleChange = (v: string) => {
        setValue(v);
        onQueryChange(v);
    };

    const handleKeyDown = (e: React.KeyboardEvent) => {
        if (e.key === 'Escape') { onClose(); return; }
        if (e.key === 'Enter') {
            e.preventDefault();
            if (e.shiftKey) onPrev(); else onNext();
        }
    };

    return (
        <div className="flex items-center gap-2 px-3 py-2 bg-[var(--bg-secondary)] border-b border-[var(--border)] text-xs">
            <Search size={13} className="text-[var(--text-muted)] flex-shrink-0" />
            <input
                ref={inputRef}
                value={value}
                onChange={e => handleChange(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="Search messages…"
                className="flex-1 bg-transparent outline-none text-[var(--text-primary)] placeholder-[var(--text-muted)] min-w-0"
            />
            {value.length > 0 && (
                <span className="text-[var(--text-muted)] flex-shrink-0 whitespace-nowrap">
                    {matchCount === 0 ? 'No results' : `${currentMatch}/${matchCount}`}
                </span>
            )}
            <div className="flex items-center gap-0.5 flex-shrink-0">
                <button
                    onClick={onPrev}
                    disabled={matchCount === 0}
                    className="p-0.5 rounded hover:bg-[var(--bg-tertiary)] disabled:opacity-30 transition-colors"
                    title="Previous match (Shift+Enter)"
                >
                    <ChevronUp size={14} />
                </button>
                <button
                    onClick={onNext}
                    disabled={matchCount === 0}
                    className="p-0.5 rounded hover:bg-[var(--bg-tertiary)] disabled:opacity-30 transition-colors"
                    title="Next match (Enter)"
                >
                    <ChevronDown size={14} />
                </button>
                <button
                    onClick={onClose}
                    className="p-0.5 rounded hover:bg-[var(--bg-tertiary)] transition-colors ml-1"
                    title="Close search (Esc)"
                >
                    <X size={14} />
                </button>
            </div>
        </div>
    );
}
