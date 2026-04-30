import { useState, useCallback } from 'react';
import { X, Plus, Trash2, Edit3, Check, BookOpen } from 'lucide-react';
import { getTemplates, saveTemplate, deleteTemplate, updateTemplate, type PromptTemplate } from '@utils/promptTemplates';

interface PromptTemplatesPanelProps {
    onClose: () => void;
    onInsert: (content: string) => void;
}

export function PromptTemplatesPanel({ onClose, onInsert }: PromptTemplatesPanelProps) {
    const [templates, setTemplates] = useState<PromptTemplate[]>(() => getTemplates());
    const [editingId, setEditingId] = useState<string | null>(null);
    const [editName, setEditName] = useState('');
    const [editContent, setEditContent] = useState('');
    const [showNew, setShowNew] = useState(false);
    const [newName, setNewName] = useState('');
    const [newContent, setNewContent] = useState('');

    const refresh = useCallback(() => setTemplates(getTemplates()), []);

    const handleAdd = () => {
        if (!newName.trim() || !newContent.trim()) return;
        saveTemplate(newName, newContent);
        setNewName('');
        setNewContent('');
        setShowNew(false);
        refresh();
    };

    const handleDelete = (id: string) => {
        deleteTemplate(id);
        refresh();
    };

    const startEdit = (t: PromptTemplate) => {
        setEditingId(t.id);
        setEditName(t.name);
        setEditContent(t.content);
    };

    const commitEdit = () => {
        if (editingId && editName.trim() && editContent.trim()) {
            updateTemplate(editingId, editName, editContent);
            refresh();
        }
        setEditingId(null);
    };

    return (
        <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
            onClick={e => { if (e.target === e.currentTarget) onClose(); }}
        >
            <div className="relative flex flex-col w-full max-w-2xl h-[70vh] max-h-[700px]
                bg-[var(--bg-primary)] border border-[var(--border)] rounded-xl shadow-2xl overflow-hidden">

                {/* Header */}
                <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--border)] bg-[var(--bg-secondary)] shrink-0">
                    <div className="flex items-center gap-2">
                        <BookOpen size={15} className="text-[var(--accent)]" />
                        <span className="text-sm font-semibold text-[var(--text-primary)]">Prompt Templates</span>
                        <span className="text-xs text-[var(--text-muted)]">{templates.length} saved</span>
                    </div>
                    <div className="flex items-center gap-2">
                        <button
                            onClick={() => { setShowNew(true); setEditingId(null); }}
                            className="flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-medium
                                bg-[var(--accent)] text-white hover:opacity-90 transition-opacity"
                        >
                            <Plus size={12} />
                            New
                        </button>
                        <button
                            onClick={onClose}
                            className="p-1.5 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                        >
                            <X size={16} />
                        </button>
                    </div>
                </div>

                {/* New template form */}
                {showNew && (
                    <div className="px-4 py-3 border-b border-[var(--border)] bg-[var(--bg-secondary)]/50 space-y-2 shrink-0">
                        <input
                            autoFocus
                            type="text"
                            placeholder="Template name"
                            value={newName}
                            onChange={e => setNewName(e.target.value)}
                            maxLength={60}
                            className="w-full px-3 py-1.5 rounded bg-[var(--bg-primary)] border border-[var(--border)]
                                text-sm text-[var(--text-primary)] outline-none focus:border-[var(--accent)] transition-colors"
                        />
                        <textarea
                            placeholder="Template content…"
                            value={newContent}
                            onChange={e => setNewContent(e.target.value)}
                            rows={3}
                            className="w-full px-3 py-2 rounded bg-[var(--bg-primary)] border border-[var(--border)]
                                text-sm text-[var(--text-primary)] outline-none focus:border-[var(--accent)]
                                transition-colors resize-none font-mono"
                        />
                        <div className="flex gap-2 justify-end">
                            <button
                                onClick={() => { setShowNew(false); setNewName(''); setNewContent(''); }}
                                className="px-3 py-1.5 rounded text-xs text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)] transition-colors"
                            >
                                Cancel
                            </button>
                            <button
                                onClick={handleAdd}
                                disabled={!newName.trim() || !newContent.trim()}
                                className="px-3 py-1.5 rounded text-xs font-medium bg-[var(--accent)] text-white
                                    hover:opacity-90 transition-opacity disabled:opacity-40 disabled:cursor-not-allowed"
                            >
                                Save template
                            </button>
                        </div>
                    </div>
                )}

                {/* Template list */}
                <div className="flex-1 overflow-y-auto px-3 py-2 space-y-1">
                    {templates.length === 0 ? (
                        <div className="flex flex-col items-center justify-center h-full text-sm text-[var(--text-muted)] gap-2">
                            <BookOpen size={32} className="opacity-30" />
                            <span>No templates yet. Click <strong>New</strong> to create one.</span>
                        </div>
                    ) : (
                        templates.map(t => (
                            <div
                                key={t.id}
                                className="group rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] overflow-hidden"
                            >
                                {editingId === t.id ? (
                                    /* Edit mode */
                                    <div className="p-3 space-y-2">
                                        <input
                                            autoFocus
                                            type="text"
                                            value={editName}
                                            onChange={e => setEditName(e.target.value)}
                                            maxLength={60}
                                            className="w-full px-2 py-1 rounded bg-[var(--bg-primary)] border border-[var(--accent)]
                                                text-sm text-[var(--text-primary)] outline-none"
                                            onKeyDown={e => { if (e.key === 'Enter') commitEdit(); if (e.key === 'Escape') setEditingId(null); }}
                                        />
                                        <textarea
                                            value={editContent}
                                            onChange={e => setEditContent(e.target.value)}
                                            rows={3}
                                            className="w-full px-2 py-1.5 rounded bg-[var(--bg-primary)] border border-[var(--border)]
                                                text-sm text-[var(--text-primary)] outline-none resize-none font-mono"
                                        />
                                        <div className="flex gap-2 justify-end">
                                            <button
                                                onClick={() => setEditingId(null)}
                                                className="px-2 py-1 rounded text-xs text-[var(--text-muted)] hover:bg-[var(--bg-hover)] transition-colors"
                                            >
                                                Cancel
                                            </button>
                                            <button
                                                onClick={commitEdit}
                                                className="flex items-center gap-1 px-2 py-1 rounded text-xs font-medium bg-[var(--accent)]/15 text-[var(--accent)] hover:bg-[var(--accent)]/25 transition-colors"
                                            >
                                                <Check size={11} /> Save
                                            </button>
                                        </div>
                                    </div>
                                ) : (
                                    /* View mode */
                                    <div className="flex items-start gap-2 p-3">
                                        <div className="flex-1 min-w-0">
                                            <div className="text-xs font-semibold text-[var(--text-primary)] truncate">{t.name}</div>
                                            <div className="text-xs text-[var(--text-secondary)] mt-0.5 line-clamp-2 font-mono">
                                                {t.content}
                                            </div>
                                        </div>
                                        <div className="flex items-center gap-1 shrink-0 opacity-0 group-hover:opacity-100 transition-opacity">
                                            <button
                                                onClick={() => { onInsert(t.content); onClose(); }}
                                                className="px-2 py-1 rounded text-xs font-medium bg-[var(--accent)]/15 text-[var(--accent)] hover:bg-[var(--accent)]/25 transition-colors"
                                                title="Insert into chat"
                                            >
                                                Use
                                            </button>
                                            <button
                                                onClick={() => startEdit(t)}
                                                className="p-1 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                                                title="Edit"
                                            >
                                                <Edit3 size={12} />
                                            </button>
                                            <button
                                                onClick={() => handleDelete(t.id)}
                                                className="p-1 rounded hover:bg-red-500/10 text-[var(--text-muted)] hover:text-red-400 transition-colors"
                                                title="Delete"
                                            >
                                                <Trash2 size={12} />
                                            </button>
                                        </div>
                                    </div>
                                )}
                            </div>
                        ))
                    )}
                </div>

                {/* Footer */}
                <div className="px-4 py-2 border-t border-[var(--border)] bg-[var(--bg-secondary)] shrink-0 text-[10px] text-[var(--text-muted)]">
                    Click <strong>Use</strong> to insert template into chat input · templates saved in localStorage
                </div>
            </div>
        </div>
    );
}
