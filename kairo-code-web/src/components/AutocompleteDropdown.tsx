import { useEffect, useRef } from 'react';
import { Terminal, FileText } from 'lucide-react';

interface AutocompleteItem {
    label: string;       // display name
    description?: string;
    value: string;       // what gets inserted
}

interface AutocompleteDropdownProps {
    items: AutocompleteItem[];
    selectedIndex: number;
    type: 'slash' | 'mention';
    onSelect: (item: AutocompleteItem) => void;
}

export function AutocompleteDropdown({ items, selectedIndex, type, onSelect }: AutocompleteDropdownProps) {
    const listRef = useRef<HTMLUListElement>(null);

    useEffect(() => {
        const el = listRef.current?.children[selectedIndex] as HTMLElement;
        el?.scrollIntoView({ block: 'nearest' });
    }, [selectedIndex]);

    if (items.length === 0) return null;

    return (
        <div className="absolute bottom-full left-0 mb-1 w-full max-w-sm z-50
            bg-[var(--bg-secondary)] border border-[var(--border)] rounded-lg shadow-xl overflow-hidden">
            {/* Header */}
            <div className="flex items-center gap-1.5 px-2.5 py-1.5 border-b border-[var(--border)]
                text-[10px] text-[var(--text-muted)] uppercase tracking-wide">
                {type === 'slash' ? <Terminal size={10} /> : <FileText size={10} />}
                {type === 'slash' ? 'Commands' : 'Files'}
            </div>
            <ul ref={listRef} className="max-h-48 overflow-y-auto py-1">
                {items.map((item, i) => (
                    <li key={item.value}>
                        <button
                            className={`w-full flex items-center gap-2 px-3 py-1.5 text-left transition-colors
                                ${i === selectedIndex
                                    ? 'bg-[var(--accent)]/15 text-[var(--text-primary)]'
                                    : 'text-[var(--text-secondary)] hover:bg-[var(--bg-hover)]'
                                }`}
                            onMouseDown={e => { e.preventDefault(); onSelect(item); }}
                        >
                            <span className="text-xs font-medium min-w-0 truncate">{item.label}</span>
                            {item.description && (
                                <span className="text-[10px] text-[var(--text-muted)] truncate flex-1">
                                    {item.description}
                                </span>
                            )}
                        </button>
                    </li>
                ))}
            </ul>
            <div className="px-2.5 py-1 border-t border-[var(--border)] text-[10px] text-[var(--text-muted)]">
                ↑↓ navigate · Enter select · Esc close
            </div>
        </div>
    );
}
