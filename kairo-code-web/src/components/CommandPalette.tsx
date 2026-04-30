import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { fuzzyMatch } from '@utils/fuzzyMatch';

export interface Command {
    id: string;
    label: string;
    description?: string;
    icon: React.ReactNode;
    shortcut?: string;
    action: () => void;
}

interface CommandPaletteProps {
    isOpen: boolean;
    onClose: () => void;
    commands: Command[];
}

function highlightMatches(text: string, query: string): React.ReactNode {
    if (!query) return text;
    const q = query.toLowerCase();
    const t = text.toLowerCase();

    // Substring match: highlight the whole match
    const idx = t.indexOf(q);
    if (idx >= 0) {
        return (
            <>
                {text.slice(0, idx)}
                <span className="text-[var(--accent)] font-medium">{text.slice(idx, idx + q.length)}</span>
                {text.slice(idx + q.length)}
            </>
        );
    }

    // Fuzzy: highlight individual matched chars
    const chars: React.ReactNode[] = [];
    let qi = 0;
    for (let i = 0; i < text.length; i++) {
        if (qi < q.length && text[i].toLowerCase() === q[qi]) {
            chars.push(<span key={i} className="text-[var(--accent)] font-medium">{text[i]}</span>);
            qi++;
        } else {
            chars.push(text[i]);
        }
    }
    return <>{chars}</>;
}

export function CommandPalette({ isOpen, onClose, commands }: CommandPaletteProps) {
    const [query, setQuery] = useState('');
    const [selectedIndex, setSelectedIndex] = useState(0);
    const inputRef = useRef<HTMLInputElement>(null);

    const filtered = useMemo(() => {
        if (!query) return commands;
        return commands
            .map(c => ({
                cmd: c,
                score: Math.max(
                    fuzzyMatch(c.label, query),
                    c.description ? fuzzyMatch(c.description, query) : -1,
                ),
            }))
            .filter(({ score }) => score >= 0)
            .sort((a, b) => b.score - a.score)
            .map(({ cmd }) => cmd);
    }, [commands, query]);

    // Reset state when opened
    useEffect(() => {
        if (isOpen) {
            setQuery('');
            setSelectedIndex(0);
        }
    }, [isOpen]);

    // Auto-focus input when opened
    useEffect(() => {
        if (isOpen) {
            inputRef.current?.focus();
        }
    }, [isOpen]);

    // Reset selection when filtered results change
    useEffect(() => {
        setSelectedIndex(0);
    }, [query]);

    const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
        if (e.key === 'ArrowDown') {
            e.preventDefault();
            setSelectedIndex(prev => Math.min(prev + 1, filtered.length - 1));
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            setSelectedIndex(prev => Math.max(prev - 1, 0));
        } else if (e.key === 'Enter') {
            e.preventDefault();
            const selected = filtered[selectedIndex];
            if (selected) {
                selected.action();
                onClose();
            }
        } else if (e.key === 'Escape') {
            e.preventDefault();
            onClose();
        }
    }, [filtered, selectedIndex, onClose]);

    if (!isOpen) return null;

    return (
        <div
            className="fixed inset-0 z-50 bg-black/50 flex items-start justify-center pt-[20vh]"
            onClick={onClose}
        >
            <div
                className="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl shadow-2xl w-[480px] overflow-hidden"
                onClick={e => e.stopPropagation()}
            >
                {/* Search input */}
                <input
                    ref={inputRef}
                    className="w-full px-4 py-3 bg-transparent text-[var(--text-primary)] placeholder-[var(--text-muted)] outline-none text-sm border-b border-[var(--border)]"
                    placeholder="Type a command..."
                    value={query}
                    onChange={e => setQuery(e.target.value)}
                    onKeyDown={handleKeyDown}
                />

                {/* Command list */}
                <div className="max-h-[320px] overflow-y-auto py-1">
                    {filtered.length === 0 ? (
                        <div className="px-4 py-8 text-center text-sm text-[var(--text-muted)]">
                            No commands found
                        </div>
                    ) : (
                        filtered.map((cmd, index) => (
                            <div
                                key={cmd.id}
                                className={`px-4 py-2.5 flex items-center gap-3 cursor-pointer text-sm
                                    ${index === selectedIndex
                                        ? 'bg-[var(--bg-hover)] text-[var(--text-primary)]'
                                        : 'text-[var(--text-secondary)]'
                                    }
                                `}
                                onClick={() => {
                                    cmd.action();
                                    onClose();
                                }}
                                onMouseEnter={() => setSelectedIndex(index)}
                            >
                                <span className="flex-shrink-0">{cmd.icon}</span>
                                <span className="flex-1">{highlightMatches(cmd.label, query)}</span>
                                {cmd.shortcut && (
                                    <span className="text-xs text-[var(--text-muted)] font-mono">
                                        {cmd.shortcut}
                                    </span>
                                )}
                            </div>
                        ))
                    )}
                </div>
            </div>
        </div>
    );
}
