import { useState, useEffect, useCallback } from 'react';
import { X, Search, Plus, Trash2, Edit3, Save, Brain, Tag } from 'lucide-react';

interface MemoryItem {
    id: string;
    content: string;
    scope: string;
    importance: number;
    tags: string[];
    timestamp: string | null;
    agentId: string | null;
}

const SCOPE_COLORS: Record<string, string> = {
    AGENT: '#6366f1',
    SESSION: '#f59e0b',
    GLOBAL: '#10b981',
};

export function MemoryPanel({ onClose }: { onClose: () => void }) {
    const [memories, setMemories] = useState<MemoryItem[]>([]);
    const [loading, setLoading] = useState(true);
    const [search, setSearch] = useState('');
    const [scope, setScope] = useState('AGENT');
    const [editingId, setEditingId] = useState<string | null>(null);
    const [editContent, setEditContent] = useState('');
    const [showAdd, setShowAdd] = useState(false);
    const [newContent, setNewContent] = useState('');
    const [newTags, setNewTags] = useState('');

    const fetchMemories = useCallback(async () => {
        setLoading(true);
        try {
            const params = new URLSearchParams({ scope });
            if (search.trim()) params.set('search', search.trim());
            const res = await fetch(`/api/memory?${params}`);
            if (res.ok) setMemories(await res.json());
        } catch { /* ignore */ }
        setLoading(false);
    }, [scope, search]);

    useEffect(() => { fetchMemories(); }, [fetchMemories]);

    useEffect(() => {
        const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [onClose]);

    const handleAdd = async () => {
        if (!newContent.trim()) return;
        const tags = newTags.split(',').map(t => t.trim()).filter(Boolean);
        await fetch('/api/memory', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ content: newContent.trim(), scope, tags }),
        });
        setNewContent('');
        setNewTags('');
        setShowAdd(false);
        fetchMemories();
    };

    const handleDelete = async (id: string) => {
        await fetch(`/api/memory/${id}`, { method: 'DELETE' });
        fetchMemories();
    };

    const handleSaveEdit = async (id: string) => {
        await fetch(`/api/memory/${id}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ content: editContent }),
        });
        setEditingId(null);
        fetchMemories();
    };

    const startEdit = (m: MemoryItem) => {
        setEditingId(m.id);
        setEditContent(m.content);
    };

    const formatTime = (ts: string | null) => {
        if (!ts) return '';
        try {
            const d = new Date(ts);
            return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        } catch { return ''; }
    };

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center"
             style={{ background: 'rgba(0,0,0,0.5)' }}
             onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
            <div className="w-full max-w-2xl max-h-[80vh] flex flex-col rounded-lg overflow-hidden"
                 style={{ background: 'var(--bg-secondary)', border: '1px solid var(--border-color)' }}>
                {/* Header */}
                <div className="flex items-center justify-between px-4 py-3"
                     style={{ borderBottom: '1px solid var(--border-color)' }}>
                    <div className="flex items-center gap-2">
                        <Brain size={16} style={{ color: 'var(--accent-color)' }} />
                        <span className="font-semibold" style={{ color: 'var(--text-primary)' }}>Memory</span>
                        <span className="text-xs px-1.5 py-0.5 rounded"
                              style={{ background: 'var(--bg-tertiary)', color: 'var(--text-secondary)' }}>
                            {memories.length}
                        </span>
                    </div>
                    <div className="flex items-center gap-2">
                        <button onClick={() => setShowAdd(!showAdd)}
                                className="text-xs px-2 py-1 rounded flex items-center gap-1"
                                style={{ background: 'var(--accent-color)', color: '#fff' }}>
                            <Plus size={12} />Add
                        </button>
                        <button onClick={onClose} style={{ color: 'var(--text-secondary)' }}>
                            <X size={16} />
                        </button>
                    </div>
                </div>

                {/* Hint */}
                <div className="px-4 py-2 text-[11px]" style={{ color: 'var(--text-secondary)', borderBottom: '1px solid var(--border-color)' }}>
                    Memories are automatically learned from conversations and injected into future sessions.
                </div>

                {/* Add Form */}
                {showAdd && (
                    <div className="px-4 py-3 space-y-2" style={{ borderBottom: '1px solid var(--border-color)' }}>
                        <textarea
                            placeholder="What should I remember?"
                            value={newContent}
                            onChange={e => setNewContent(e.target.value)}
                            rows={3}
                            className="w-full px-2 py-1 rounded text-sm outline-none resize-none"
                            style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)', color: 'var(--text-primary)' }}
                        />
                        <div className="flex gap-2 items-center">
                            <input type="text" placeholder="Tags (comma-separated)"
                                   value={newTags} onChange={e => setNewTags(e.target.value)}
                                   className="flex-1 px-2 py-1 rounded text-xs outline-none"
                                   style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)', color: 'var(--text-primary)' }} />
                            <button onClick={handleAdd} disabled={!newContent.trim()}
                                    className="px-3 py-1 rounded text-xs"
                                    style={{ background: 'var(--accent-color)', color: '#fff', opacity: !newContent.trim() ? 0.5 : 1 }}>
                                Save
                            </button>
                        </div>
                    </div>
                )}

                {/* Scope tabs + Search */}
                <div className="flex items-center gap-2 px-4 py-2" style={{ borderBottom: '1px solid var(--border-color)' }}>
                    {['AGENT', 'SESSION', 'GLOBAL'].map(s => (
                        <button key={s} onClick={() => setScope(s)}
                                className="text-xs px-2 py-1 rounded"
                                style={{
                                    background: scope === s ? (SCOPE_COLORS[s] || '#6b7280') : 'transparent',
                                    color: scope === s ? '#fff' : 'var(--text-secondary)',
                                }}>
                            {s}
                        </button>
                    ))}
                    <div className="flex-1" />
                    <div className="relative">
                        <Search size={12} className="absolute left-2 top-1/2 -translate-y-1/2" style={{ color: 'var(--text-secondary)' }} />
                        <input type="text" placeholder="Search..." value={search}
                               onChange={e => setSearch(e.target.value)}
                               className="pl-6 pr-2 py-1 rounded text-xs outline-none w-40"
                               style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)', color: 'var(--text-primary)' }} />
                    </div>
                </div>

                {/* Memory List */}
                <div className="flex-1 overflow-y-auto px-4 py-2" style={{ minHeight: 0 }}>
                    {loading ? (
                        <p className="text-sm text-center py-8" style={{ color: 'var(--text-secondary)' }}>Loading...</p>
                    ) : memories.length === 0 ? (
                        <p className="text-sm text-center py-8" style={{ color: 'var(--text-secondary)' }}>
                            No memories yet. They&apos;ll be learned automatically from your conversations.
                        </p>
                    ) : memories.map(m => (
                        <div key={m.id} className="py-3" style={{ borderBottom: '1px solid var(--border-color)' }}>
                            {editingId === m.id ? (
                                <div className="space-y-2">
                                    <textarea value={editContent} onChange={e => setEditContent(e.target.value)}
                                              rows={3}
                                              className="w-full px-2 py-1 rounded text-sm outline-none resize-none"
                                              style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)', color: 'var(--text-primary)' }} />
                                    <div className="flex gap-2">
                                        <button onClick={() => handleSaveEdit(m.id)}
                                                className="text-xs px-2 py-1 rounded flex items-center gap-1"
                                                style={{ background: 'var(--accent-color)', color: '#fff' }}>
                                            <Save size={10} />Save
                                        </button>
                                        <button onClick={() => setEditingId(null)}
                                                className="text-xs px-2 py-1 rounded"
                                                style={{ color: 'var(--text-secondary)' }}>
                                            Cancel
                                        </button>
                                    </div>
                                </div>
                            ) : (
                                <>
                                    <div className="flex items-start justify-between gap-2">
                                        <p className="text-sm flex-1" style={{ color: 'var(--text-primary)' }}>
                                            {m.content}
                                        </p>
                                        <div className="flex gap-1 shrink-0">
                                            <button onClick={() => startEdit(m)} className="p-1 rounded hover:opacity-80"
                                                    style={{ color: 'var(--text-secondary)' }} title="Edit">
                                                <Edit3 size={13} />
                                            </button>
                                            <button onClick={() => handleDelete(m.id)} className="p-1 rounded hover:opacity-80"
                                                    style={{ color: '#ef4444' }} title="Delete">
                                                <Trash2 size={13} />
                                            </button>
                                        </div>
                                    </div>
                                    <div className="flex items-center gap-2 mt-1">
                                        {m.tags.length > 0 && m.tags.map(t => (
                                            <span key={t} className="text-[10px] px-1.5 py-0.5 rounded flex items-center gap-0.5"
                                                  style={{ background: 'var(--bg-primary)', color: 'var(--text-secondary)', border: '1px solid var(--border-color)' }}>
                                                <Tag size={8} />{t}
                                            </span>
                                        ))}
                                        <span className="text-[10px]" style={{ color: 'var(--text-secondary)' }}>
                                            {formatTime(m.timestamp)}
                                        </span>
                                    </div>
                                </>
                            )}
                        </div>
                    ))}
                </div>
            </div>
        </div>
    );
}
