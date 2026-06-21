import { useState, useEffect, useRef, useCallback } from 'react';
import { X, Loader2, FileText, AtSign, ChevronRight, ChevronDown, CaseSensitive, Regex, WholeWord } from 'lucide-react';
import { searchFiles, getFileContent } from '@api/config';
import type { SearchMatch, SearchResponse } from '@/types/agent';
import { useWorkspaceStore } from '@store/workspaceStore';

interface SearchPanelProps {
    isOpen: boolean;
    onClose: () => void;
    onInsertResult: (text: string) => void;
    onOpenFile?: (path: string, line?: number) => void;
    workspaceId?: string;
    embedded?: boolean;
}

export function SearchPanel({ isOpen, onClose, onInsertResult, onOpenFile, workspaceId: propWorkspaceId, embedded = false }: SearchPanelProps) {
    const storeWorkspaceId = useWorkspaceStore((s) => s.currentWorkspaceId);
    const workspaceId = propWorkspaceId ?? storeWorkspaceId ?? undefined;

    const [query, setQuery] = useState('');
    const [results, setResults] = useState<SearchResponse | null>(null);
    const [isSearching, setIsSearching] = useState(false);
    const [error, setError] = useState<string | null>(null);

    // Search options
    const [useRegex, setUseRegex] = useState(false);
    const [caseSensitive, setCaseSensitive] = useState(false);
    const [wholeWord, setWholeWord] = useState(false);
    const [showFilters, setShowFilters] = useState(false);
    const [includeGlob, setIncludeGlob] = useState('');
    const [excludeGlob, setExcludeGlob] = useState('');

    // Collapsed file groups
    const [collapsedFiles, setCollapsedFiles] = useState<Set<string>>(new Set());

    const inputRef = useRef<HTMLInputElement>(null);
    const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    useEffect(() => {
        if (isOpen) {
            inputRef.current?.focus();
            setError(null);
        }
    }, [isOpen]);

    const doSearch = useCallback(() => {
        if (!isOpen || query.length < 2) {
            if (query.length < 2) setResults(null);
            return;
        }

        setIsSearching(true);
        setError(null);

        let searchQuery = query;
        if (wholeWord && !useRegex) {
            searchQuery = `\\b${query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`;
        }

        searchFiles({
            q: wholeWord && !useRegex ? searchQuery : query,
            regex: useRegex || wholeWord,
            caseSensitive,
            include: includeGlob || undefined,
            exclude: excludeGlob || undefined,
            contextLines: 0,
            limit: 100,
            workspaceId,
        })
            .then((res) => { setResults(res); setIsSearching(false); })
            .catch((err) => { setError(err.message); setIsSearching(false); });
    }, [query, isOpen, workspaceId, useRegex, caseSensitive, wholeWord, includeGlob, excludeGlob]);

    useEffect(() => {
        if (debounceRef.current) clearTimeout(debounceRef.current);
        debounceRef.current = setTimeout(doSearch, 400);
        return () => { if (debounceRef.current) clearTimeout(debounceRef.current); };
    }, [doSearch]);

    const handleMatchClick = useCallback((match: SearchMatch) => {
        if (onOpenFile) {
            onOpenFile(match.file, match.line);
        } else {
            onInsertResult(`file:${match.file}:L${match.line}\n${match.preview}`);
        }
    }, [onInsertResult, onOpenFile]);

    const handleFileClick = useCallback(async (file: string) => {
        if (onOpenFile) { onOpenFile(file); return; }
        try {
            const content = await getFileContent(file, workspaceId);
            onInsertResult(`\`\`\`${content.language}\n// ${file}\n${content.content}\n\`\`\`\n`);
        } catch { /* ignore */ }
    }, [onInsertResult, onOpenFile, workspaceId]);

    const toggleFileCollapse = (file: string) => {
        setCollapsedFiles(prev => {
            const next = new Set(prev);
            if (next.has(file)) next.delete(file); else next.add(file);
            return next;
        });
    };

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

    const toggleBtn = (active: boolean, onClick: () => void, title: string, children: React.ReactNode) => (
        <button
            onClick={onClick}
            className={`w-7 h-7 flex items-center justify-center rounded text-xs font-mono transition-colors ${
                active
                    ? 'bg-[var(--accent)]/20 text-[var(--accent)] border border-[var(--accent)]/40'
                    : 'text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)] border border-transparent'
            }`}
            title={title}
        >
            {children}
        </button>
    );

    const body = (
        <>
            {/* Search input + toggles */}
            <div className="px-3 py-2 border-b border-[var(--border)] space-y-2">
                <div className="flex items-center gap-1.5">
                    <input
                        ref={inputRef}
                        type="text"
                        className="flex-1 min-w-0 px-2 py-1.5 text-sm bg-[var(--bg-primary)] border border-[var(--border)] rounded outline-none text-[var(--text-primary)] placeholder-[var(--text-muted)] focus:border-[var(--accent)]"
                        placeholder="Search..."
                        value={query}
                        onChange={(e) => setQuery(e.target.value)}
                        onKeyDown={(e) => { if (e.key === 'Enter') doSearch(); }}
                    />
                    {toggleBtn(caseSensitive, () => setCaseSensitive(v => !v), 'Match Case',
                        <CaseSensitive size={14} />)}
                    {toggleBtn(wholeWord, () => setWholeWord(v => !v), 'Match Whole Word',
                        <WholeWord size={14} />)}
                    {toggleBtn(useRegex, () => setUseRegex(v => !v), 'Use Regular Expression',
                        <Regex size={14} />)}
                    {!embedded && (
                        <button onClick={onClose} className="p-1 text-[var(--text-muted)] hover:text-[var(--text-primary)]" title="Close">
                            <X size={16} />
                        </button>
                    )}
                </div>
                {/* File filters (collapsible) */}
                <div>
                    <button
                        onClick={() => setShowFilters(v => !v)}
                        className="text-[10px] text-[var(--text-muted)] hover:text-[var(--text-primary)] flex items-center gap-1"
                    >
                        {showFilters ? <ChevronDown size={10} /> : <ChevronRight size={10} />}
                        files to include / exclude
                    </button>
                    {showFilters && (
                        <div className="mt-1 space-y-1">
                            <input
                                type="text"
                                className="w-full px-2 py-1 text-xs bg-[var(--bg-primary)] border border-[var(--border)] rounded outline-none text-[var(--text-primary)] placeholder-[var(--text-muted)] focus:border-[var(--accent)]"
                                placeholder="Include: *.java, *.ts, *.py"
                                value={includeGlob}
                                onChange={(e) => setIncludeGlob(e.target.value)}
                            />
                            <input
                                type="text"
                                className="w-full px-2 py-1 text-xs bg-[var(--bg-primary)] border border-[var(--border)] rounded outline-none text-[var(--text-primary)] placeholder-[var(--text-muted)] focus:border-[var(--accent)]"
                                placeholder="Exclude: *test*, *mock*"
                                value={excludeGlob}
                                onChange={(e) => setExcludeGlob(e.target.value)}
                            />
                        </div>
                    )}
                </div>
            </div>

            {/* Results */}
            <div className="flex-1 overflow-y-auto">
                {isSearching && (
                    <div className="flex items-center justify-center gap-2 py-8 text-[var(--text-muted)]">
                        <Loader2 size={16} className="animate-spin" />
                        <span className="text-xs">Searching...</span>
                    </div>
                )}

                {error && (
                    <div className="px-3 py-3 text-xs text-red-400">{error}</div>
                )}

                {!isSearching && !error && query.length >= 2 && matchCount === 0 && (
                    <div className="px-3 py-8 text-center text-xs text-[var(--text-muted)]">
                        No results found for &ldquo;{query}&rdquo;
                    </div>
                )}

                {!isSearching && !error && results && matchCount > 0 && (
                    <div className="py-1">
                        <div className="px-3 py-1.5 text-[10px] text-[var(--text-muted)] border-b border-[var(--border)]">
                            {matchCount} result{matchCount !== 1 ? 's' : ''} in {fileCount} file{fileCount !== 1 ? 's' : ''}
                            {results.truncated && ' (truncated)'}
                        </div>

                        {Array.from(filesMap.entries()).map(([file, matches]) => {
                            const collapsed = collapsedFiles.has(file);
                            return (
                                <div key={file} className="border-b border-[var(--border)] last:border-0">
                                    <div className="group flex items-center gap-1 px-2 py-1.5 hover:bg-[var(--bg-hover)] transition-colors">
                                        <button
                                            onClick={() => toggleFileCollapse(file)}
                                            className="flex items-center gap-1.5 flex-1 min-w-0 text-left"
                                        >
                                            {collapsed ? <ChevronRight size={12} className="shrink-0 text-[var(--text-muted)]" />
                                                        : <ChevronDown size={12} className="shrink-0 text-[var(--text-muted)]" />}
                                            <FileText size={13} className="shrink-0 text-[var(--text-muted)]" />
                                            <span className="text-xs text-[var(--text-primary)] truncate flex-1 font-mono">
                                                {file}
                                            </span>
                                            <span className="text-[10px] text-[var(--text-muted)] shrink-0 px-1.5 py-0.5 rounded-full bg-[var(--bg-hover)]">
                                                {matches.length}
                                            </span>
                                        </button>
                                        {onOpenFile && (
                                            <button
                                                className="shrink-0 opacity-0 group-hover:opacity-100 p-0.5 text-[var(--text-muted)] hover:text-[var(--accent)] transition-opacity"
                                                onClick={() => handleFileClick(file)}
                                                title="Open file"
                                            >
                                                <AtSign size={11} />
                                            </button>
                                        )}
                                    </div>
                                    {!collapsed && (
                                        <div className="pl-8 pr-2 pb-1">
                                            {matches.map((m, i) => (
                                                <button
                                                    key={i}
                                                    className="w-full text-left text-[11px] py-0.5 px-1.5 rounded hover:bg-[var(--bg-hover)] cursor-pointer font-mono truncate flex items-baseline gap-1.5"
                                                    onClick={() => handleMatchClick(m)}
                                                    title={`Line ${m.line}: ${m.preview}`}
                                                >
                                                    <span className="text-[var(--text-muted)] shrink-0 w-8 text-right">
                                                        {m.line}
                                                    </span>
                                                    <HighlightedPreview text={m.preview} query={query} useRegex={useRegex} caseSensitive={caseSensitive} />
                                                </button>
                                            ))}
                                        </div>
                                    )}
                                </div>
                            );
                        })}
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
            onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
        >
            <div className="w-[560px] max-h-[70vh] flex flex-col bg-[var(--bg-secondary)] border border-[var(--border)] rounded-lg shadow-xl overflow-hidden">
                {body}
            </div>
        </div>
    );
}

function HighlightedPreview({ text, query, useRegex, caseSensitive }: {
    text: string; query: string; useRegex: boolean; caseSensitive: boolean;
}) {
    if (!query || query.length < 2) {
        return <span className="text-[var(--text-secondary)] truncate">{text}</span>;
    }

    try {
        const escaped = useRegex ? query : query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        const flags = caseSensitive ? 'g' : 'gi';
        const re = new RegExp(`(${escaped})`, flags);
        const parts = text.split(re);

        return (
            <span className="text-[var(--text-secondary)] truncate">
                {parts.map((part, i) =>
                    re.test(part)
                        ? <mark key={i} className="bg-[var(--accent)]/30 text-[var(--accent)] rounded-sm px-0.5">{part}</mark>
                        : <span key={i}>{part}</span>
                )}
            </span>
        );
    } catch {
        return <span className="text-[var(--text-secondary)] truncate">{text}</span>;
    }
}
