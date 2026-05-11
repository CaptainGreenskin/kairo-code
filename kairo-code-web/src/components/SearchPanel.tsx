import { useState, useEffect, useRef, useCallback } from 'react';
import { X, Loader2, FileText, AtSign } from 'lucide-react';
import { searchFiles } from '@api/config';
import { getFileContent } from '@api/config';
import type { SearchMatch, SearchResponse } from '@/types/agent';

interface SearchPanelProps {
    isOpen: boolean;
    onClose: () => void;
    onInsertResult: (text: string) => void;
    /** When provided, clicking a result opens the file in the editor at the matched line. */
    onOpenFile?: (path: string, line?: number) => void;
    workspaceId?: string;
    /** When true, render inline (no fixed/modal backdrop). Host controls layout. */
    embedded?: boolean;
}

export function SearchPanel({ isOpen, onClose, onInsertResult, onOpenFile, workspaceId, embedded = false }: SearchPanelProps) {
    const [query, setQuery] = useState('');
    const [results, setResults] = useState<SearchResponse | null>(null);
    const [isSearching, setIsSearching] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const inputRef = useRef<HTMLInputElement>(null);
    const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    // Auto-focus input when panel opens
    useEffect(() => {
        if (isOpen) {
            inputRef.current?.focus();
            setResults(null);
            setError(null);
        }
    }, [isOpen]);

    // Debounced search
    useEffect(() => {
        if (debounceRef.current) clearTimeout(debounceRef.current);

        if (!isOpen || query.length < 2) {
            if (query.length < 2 && query.length > 0) {
                setResults(null);
            }
            return;
        }

        setIsSearching(true);
        setError(null);

        debounceRef.current = setTimeout(() => {
            searchFiles(query, undefined, 50, workspaceId)
                .then((res) => {
                    setResults(res);
                    setIsSearching(false);
                })
                .catch((err) => {
                    setError(err.message);
                    setIsSearching(false);
                });
        }, 300);

        return () => {
            if (debounceRef.current) clearTimeout(debounceRef.current);
        };
    }, [query, isOpen, workspaceId]);

    const handleBackdropClick = useCallback(
        (e: React.MouseEvent) => {
            if (e.target === e.currentTarget) {
                onClose();
            }
        },
        [onClose],
    );

    const handleMatchClick = useCallback(
        (match: SearchMatch) => {
            if (onOpenFile) {
                onOpenFile(match.file, match.line);
            } else {
                onInsertResult(`file:${match.file}:L${match.line}\n${match.preview}`);
            }
            onClose();
        },
        [onInsertResult, onOpenFile, onClose],
    );

    const handleFileClick = useCallback(
        async (file: string) => {
            if (onOpenFile) {
                onOpenFile(file);
                onClose();
                return;
            }
            try {
                const content = await getFileContent(file, workspaceId);
                onInsertResult(`\`\`\`${content.language}\n// ${file}\n${content.content}\n\`\`\`\n`);
                onClose();
            } catch {
                // Ignore
            }
        },
        [onInsertResult, onOpenFile, onClose, workspaceId],
    );

    const handleInsertFile = useCallback(
        async (file: string, e: React.MouseEvent) => {
            e.stopPropagation();
            try {
                const content = await getFileContent(file, workspaceId);
                onInsertResult(`\`\`\`${content.language}\n// ${file}\n${content.content}\n\`\`\`\n`);
                onClose();
            } catch {
                // Ignore
            }
        },
        [onInsertResult, onClose, workspaceId],
    );

    if (!isOpen) return null;

    // Group matches by file
    const filesMap = new Map<string, SearchMatch[]>();
    if (results) {
        for (const m of results.matches) {
            const list = filesMap.get(m.file) || [];
            list.push(m);
            filesMap.set(m.file, list);
        }
    }
    const fileCount = filesMap.size;
    const matchCount = results?.matches.length ?? 0;

    const body = (
        <>
            <div className="flex items-center gap-3 px-4 py-3 border-b border-[var(--border)]">
                <span className="text-[var(--text-muted)]">
                    &#128269;
                </span>
                <input
                    ref={inputRef}
                    type="text"
                    className="flex-1 bg-transparent outline-none text-[var(--text-primary)] placeholder-[var(--text-muted)]"
                    placeholder="Search workspace..."
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                />
                {!embedded && (
                    <button
                        onClick={onClose}
                        className="text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                        title="Close (ESC)"
                    >
                        <X size={18} />
                    </button>
                )}
            </div>

                {/* Results */}
                <div className="flex-1 overflow-y-auto">
                    {isSearching && (
                        <div className="flex items-center justify-center gap-2 py-8 text-[var(--text-muted)]">
                            <Loader2 size={16} className="animate-spin" />
                            <span>Searching...</span>
                        </div>
                    )}

                    {error && (
                        <div className="px-4 py-3 text-sm text-[var(--color-danger)]">
                            {error}
                        </div>
                    )}

                    {!isSearching && !error && query.length >= 2 && matchCount === 0 && (
                        <div className="px-4 py-8 text-center text-[var(--text-muted)]">
                            No matches found
                        </div>
                    )}

                    {!isSearching && !error && results && matchCount > 0 && (
                        <div className="px-4 py-2">
                            <div className="text-xs text-[var(--text-muted)] mb-2">
                                matched {matchCount} result{matchCount !== 1 ? 's' : ''} in {fileCount} file{fileCount !== 1 ? 's' : ''}
                            </div>

                            {Array.from(filesMap.entries()).map(([file, matches]) => (
                                <div key={file} className="mb-3">
                                    <div className="group flex items-center gap-1 py-1">
                                        <button
                                            className="flex-1 text-left text-sm font-medium text-[var(--color-info)] hover:underline cursor-pointer flex items-center gap-1.5 min-w-0"
                                            onClick={() => handleFileClick(file)}
                                            title={onOpenFile ? 'Open in editor' : 'Insert entire file'}
                                        >
                                            <FileText size={14} className="shrink-0" />
                                            <span className="truncate">{file}</span>
                                        </button>
                                        {onOpenFile && (
                                            <button
                                                className="shrink-0 opacity-0 group-hover:opacity-100 p-0.5 text-[var(--text-muted)] hover:text-[var(--accent)] transition-opacity"
                                                onClick={(e) => handleInsertFile(file, e)}
                                                title="Insert entire file into chat"
                                            >
                                                <AtSign size={11} />
                                            </button>
                                        )}
                                    </div>
                                    <div className="ml-5">
                                        {matches.map((m, i) => (
                                            <button
                                                key={i}
                                                className="w-full text-left text-sm py-0.5 px-2 rounded hover:bg-[var(--bg-hover)] cursor-pointer font-mono text-[var(--text-secondary)] truncate"
                                                onClick={() => handleMatchClick(m)}
                                                title="Insert this match"
                                            >
                                                <span className="text-[var(--text-muted)] mr-2">
                                                    L{m.line}
                                                </span>
                                                {m.preview}
                                            </button>
                                        ))}
                                    </div>
                                </div>
                            ))}

                        {results.truncated && (
                            <div className="text-xs text-[var(--text-muted)] py-2 border-t border-[var(--border)]">
                                Truncated &#8212; showing first {results.matches.length}
                            </div>
                        )}
                    </div>
                )}
            </div>
        </>
    );

    if (embedded) {
        return <div className="flex flex-col h-full bg-[var(--bg-primary)] overflow-hidden">{body}</div>;
    }

    return (
        <div
            className="fixed inset-0 z-50 flex items-start justify-center pt-[15vh] bg-black/40"
            onClick={handleBackdropClick}
        >
            <div className="w-[560px] max-h-[70vh] flex flex-col bg-[var(--bg-secondary)] border border-[var(--border)] rounded-lg shadow-xl overflow-hidden">
                {body}
            </div>
        </div>
    );
}
