import { useState, useRef, useEffect, useCallback } from 'react';
import { Search, X, MessageSquare, User, Bot, Loader2 } from 'lucide-react';

interface SearchHit {
    sessionId: string;
    sessionName: string;
    savedAt: number;
    messageId: string;
    role: string;
    snippet: string;
    matchIndex: number;
}

interface SessionSearchPanelProps {
    isOpen: boolean;
    onClose: () => void;
    onSelectSession: (sessionId: string) => void;
}

export function SessionSearchPanel({ isOpen, onClose, onSelectSession }: SessionSearchPanelProps) {
    const [query, setQuery] = useState('');
    const [hits, setHits] = useState<SearchHit[]>([]);
    const [loading, setLoading] = useState(false);
    const inputRef = useRef<HTMLInputElement>(null);
    const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    useEffect(() => {
        if (isOpen) {
            inputRef.current?.focus();
            setQuery('');
            setHits([]);
        }
    }, [isOpen]);

    const search = useCallback((q: string) => {
        if (debounceRef.current) clearTimeout(debounceRef.current);
        if (q.trim().length < 2) { setHits([]); return; }
        debounceRef.current = setTimeout(async () => {
            setLoading(true);
            try {
                const res = await fetch(`/api/sessions/search?q=${encodeURIComponent(q)}&limit=30`);
                if (res.ok) setHits(await res.json());
            } finally {
                setLoading(false);
            }
        }, 300);
    }, []);

    if (!isOpen) return null;

    const groupedBySession = hits.reduce<Record<string, { name: string; savedAt: number; hits: SearchHit[] }>>(
        (acc, h) => {
            if (!acc[h.sessionId]) acc[h.sessionId] = { name: h.sessionName, savedAt: h.savedAt, hits: [] };
            acc[h.sessionId].hits.push(h);
            return acc;
        }, {}
    );

    const highlight = (text: string, q: string) => {
        if (!q.trim()) return text;
        const idx = text.toLowerCase().indexOf(q.toLowerCase());
        if (idx < 0) return text;
        return (
            <>
                {text.slice(0, idx)}
                <mark className="bg-[var(--accent)]/30 text-[var(--accent)] rounded-sm px-0.5">{text.slice(idx, idx + q.length)}</mark>
                {text.slice(idx + q.length)}
            </>
        );
    };

    return (
        <div className="fixed inset-0 z-50 flex items-start justify-center pt-20 bg-black/50 backdrop-blur-sm" onClick={onClose}>
            <div
                className="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl shadow-2xl w-full max-w-2xl max-h-[70vh] flex flex-col overflow-hidden"
                onClick={e => e.stopPropagation()}
            >
                {/* Search input */}
                <div className="flex items-center gap-2 px-4 py-3 border-b border-[var(--border)]">
                    <Search size={14} className="text-[var(--text-muted)] shrink-0" />
                    <input
                        ref={inputRef}
                        value={query}
                        onChange={e => { setQuery(e.target.value); search(e.target.value); }}
                        placeholder="Search across all sessions…"
                        className="flex-1 bg-transparent text-sm text-[var(--text-primary)] placeholder:text-[var(--text-muted)] outline-none"
                    />
                    {loading && <Loader2 size={13} className="animate-spin text-[var(--text-muted)]" />}
                    <button onClick={onClose} className="text-[var(--text-muted)] hover:text-[var(--text-primary)]">
                        <X size={14} />
                    </button>
                </div>

                {/* Results */}
                <div className="flex-1 overflow-y-auto">
                    {query.length >= 2 && hits.length === 0 && !loading && (
                        <div className="flex flex-col items-center py-10 text-[var(--text-muted)]">
                            <Search size={28} className="opacity-30 mb-2" />
                            <p className="text-sm">No results found</p>
                        </div>
                    )}
                    {Object.entries(groupedBySession).map(([sessionId, group]) => (
                        <div key={sessionId} className="border-b border-[var(--border)] last:border-0">
                            {/* Session header */}
                            <button
                                onClick={() => { onSelectSession(sessionId); onClose(); }}
                                className="w-full flex items-center gap-2 px-4 py-2 bg-[var(--bg-primary)] hover:bg-[var(--bg-hover)] text-left"
                            >
                                <MessageSquare size={11} className="text-[var(--accent)] shrink-0" />
                                <span className="text-xs font-semibold text-[var(--text-primary)] flex-1 truncate">
                                    {group.name || sessionId.slice(0, 16)}
                                </span>
                                <span className="text-xs text-[var(--text-muted)]">
                                    {group.hits.length} match{group.hits.length !== 1 ? 'es' : ''}
                                </span>
                            </button>
                            {/* Hits */}
                            {group.hits.map((hit, i) => (
                                <button
                                    key={`${hit.messageId}-${i}`}
                                    onClick={() => { onSelectSession(sessionId); onClose(); }}
                                    className="w-full flex items-start gap-2 px-4 py-2 hover:bg-[var(--bg-hover)] text-left"
                                >
                                    {hit.role === 'user'
                                        ? <User size={11} className="text-blue-400 mt-0.5 shrink-0" />
                                        : <Bot size={11} className="text-emerald-400 mt-0.5 shrink-0" />
                                    }
                                    <p className="text-xs text-[var(--text-muted)] leading-relaxed font-mono">
                                        {highlight(hit.snippet, query)}
                                    </p>
                                </button>
                            ))}
                        </div>
                    ))}
                </div>

                {hits.length > 0 && (
                    <div className="px-4 py-1.5 border-t border-[var(--border)] bg-[var(--bg-primary)]">
                        <p className="text-xs text-[var(--text-muted)]">{hits.length} results across {Object.keys(groupedBySession).length} sessions</p>
                    </div>
                )}
            </div>
        </div>
    );
}
