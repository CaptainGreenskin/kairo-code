import { useState, useEffect, useRef, useCallback } from 'react';
import { Folder, File, ChevronRight } from 'lucide-react';
import { listFiles, getFileContent } from '@api/config';
import type { FileEntry } from '@/types/agent';

const MAX_FILE_SIZE = 100_000;

interface FilePickerProps {
    query: string;
    onSelect: (block: string) => void;
    onClose: () => void;
}

export function FilePicker({ query, onSelect, onClose }: FilePickerProps) {
    const [entries, setEntries] = useState<FileEntry[]>([]);
    const [currentDir, setCurrentDir] = useState('');
    const [selectedIndex, setSelectedIndex] = useState(0);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const listRef = useRef<HTMLDivElement>(null);

    const loadDir = useCallback(async (dir: string) => {
        setLoading(true);
        setError(null);
        setSelectedIndex(0);
        try {
            const result = await listFiles(dir || undefined);
            setEntries(result);
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed to load directory');
            setEntries([]);
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        loadDir(currentDir);
    }, [currentDir, loadDir]);

    // Filter entries by query
    const filtered = query
        ? entries.filter((e) => e.name.toLowerCase().startsWith(query.toLowerCase()))
        : entries;

    // Sort: directories first, then files
    const sorted = [...filtered].sort((a, b) => {
        if (a.isDir && !b.isDir) return -1;
        if (!a.isDir && b.isDir) return 1;
        return a.name.localeCompare(b.name);
    });

    const handleSelect = useCallback(
        async (entry: FileEntry) => {
            if (entry.isDir) {
                setCurrentDir(entry.path);
                return;
            }
            if (entry.size > MAX_FILE_SIZE) {
                onSelect(
                    `\n\n[File too large: ${entry.name} (${(entry.size / 1024).toFixed(1)}KB > 100KB)]\n`,
                );
                onClose();
                return;
            }
            try {
                const content = await getFileContent(entry.path);
                const block = `\n\n\`\`\`${content.language}\n// ${entry.name}\n${content.content}\n\`\`\`\n`;
                onSelect(block);
            } catch (e) {
                onSelect(`\n\n[Error reading file: ${entry.name}]\n`);
            }
            onClose();
        },
        [onSelect, onClose],
    );

    const handleKeyDown = useCallback(
        (e: React.KeyboardEvent) => {
            if (e.key === 'ArrowDown') {
                e.preventDefault();
                setSelectedIndex((prev) => Math.min(prev + 1, sorted.length - 1));
            } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                setSelectedIndex((prev) => Math.max(prev - 1, 0));
            } else if (e.key === 'Enter') {
                e.preventDefault();
                if (sorted[selectedIndex]) {
                    handleSelect(sorted[selectedIndex]);
                }
            } else if (e.key === 'Escape') {
                onClose();
            } else if (e.key === 'Backspace' && !query && currentDir) {
                e.preventDefault();
                // Go to parent directory
                const parts = currentDir.split('/');
                parts.pop();
                setCurrentDir(parts.join('/'));
            }
        },
        [sorted, selectedIndex, handleSelect, onClose, query, currentDir],
    );

    // Scroll selected item into view
    useEffect(() => {
        const selected = listRef.current?.querySelector('[data-selected="true"]');
        selected?.scrollIntoView({ block: 'nearest' });
    }, [selectedIndex]);

    return (
        <div
            className="absolute bottom-full left-0 right-0 mb-1 bg-[var(--bg-secondary)] border border-[var(--border)] rounded-lg shadow-lg overflow-hidden z-50"
            onKeyDown={handleKeyDown}
            onClick={(e) => e.stopPropagation()}
        >
            {/* Breadcrumb */}
            {currentDir && (
                <div className="px-3 py-1.5 text-xs text-[var(--text-muted)] border-b border-[var(--border)] flex items-center gap-1">
                    <button
                        onClick={() => {
                            const parts = currentDir.split('/');
                            parts.pop();
                            setCurrentDir(parts.join('/'));
                        }}
                        className="hover:text-[var(--text-primary)] transition-colors"
                    >
                        ..
                    </button>
                    <ChevronRight size={12} />
                    <span className="truncate">{currentDir}</span>
                </div>
            )}

            {/* File list */}
            <div ref={listRef} className="max-h-60 overflow-y-auto">
                {loading && (
                    <div className="px-3 py-2 text-sm text-[var(--text-muted)]">Loading...</div>
                )}
                {error && (
                    <div className="px-3 py-2 text-sm text-[var(--color-danger)]">{error}</div>
                )}
                {!loading && !error && sorted.length === 0 && (
                    <div className="px-3 py-2 text-sm text-[var(--text-muted)]">No matches</div>
                )}
                {sorted.map((entry, i) => (
                    <button
                        key={entry.path}
                        data-selected={i === selectedIndex}
                        className={`w-full text-left px-3 py-1.5 text-sm flex items-center gap-2 transition-colors ${
                            i === selectedIndex
                                ? 'bg-[var(--color-primary)]/10 text-[var(--text-primary)]'
                                : 'text-[var(--text-secondary)] hover:bg-[var(--bg-hover)]'
                        }`}
                        onClick={() => handleSelect(entry)}
                        onMouseEnter={() => setSelectedIndex(i)}
                    >
                        {entry.isDir ? (
                            <Folder size={14} className="text-[var(--color-info)]" />
                        ) : (
                            <File size={14} className="text-[var(--text-muted)]" />
                        )}
                        <span className="truncate">{entry.name}</span>
                        {!entry.isDir && entry.size > 0 && (
                            <span className="ml-auto text-xs text-[var(--text-muted)] shrink-0">
                                {(entry.size / 1024).toFixed(1)}KB
                            </span>
                        )}
                    </button>
                ))}
            </div>
        </div>
    );
}
