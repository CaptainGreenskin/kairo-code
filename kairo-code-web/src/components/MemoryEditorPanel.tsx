import { useState, useEffect, useCallback } from 'react';
import { X, Save, Eye, Edit3, FileText, RefreshCw } from 'lucide-react';
import { LazyMarkdown } from './LazyMarkdown';

interface MemoryEditorPanelProps {
    workingDir: string;
    onClose: () => void;
}

const MEMORY_FILES = ['CLAUDE.md', 'CLAUDE.local.md'] as const;
type MemoryFile = typeof MEMORY_FILES[number];

const TEMPLATES: Record<string, string> = {
    'Project Overview': '## Project Overview\n\n',
    'Tech Stack': '## Tech Stack\n- Backend: \n- Frontend: \n',
    'Coding Style': '## Coding Style\n- \n',
    'Important Notes': '## Important Notes\n- \n',
};

async function fetchFileContent(path: string): Promise<string | null> {
    try {
        const res = await fetch(`/api/files/content?path=${encodeURIComponent(path)}`);
        if (res.status === 404) return '';
        if (!res.ok) return null;
        const data = await res.json() as { content: string };
        return data.content;
    } catch {
        return null;
    }
}

async function saveFileContent(path: string, content: string): Promise<boolean> {
    try {
        const res = await fetch(`/api/files/content?path=${encodeURIComponent(path)}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'text/plain' },
            body: content,
        });
        return res.ok;
    } catch {
        return false;
    }
}

export function MemoryEditorPanel({ workingDir: _workingDir, onClose }: MemoryEditorPanelProps) {
    const [activeFile, setActiveFile] = useState<MemoryFile>('CLAUDE.md');
    const [content, setContent] = useState('');
    const [originalContent, setOriginalContent] = useState('');
    const [isPreview, setIsPreview] = useState(false);
    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [saveError, setSaveError] = useState<string | null>(null);
    const [showTemplates, setShowTemplates] = useState(false);

    const isDirty = content !== originalContent;

    const loadFile = useCallback(async (file: MemoryFile) => {
        setLoading(true);
        setSaveError(null);
        const text = await fetchFileContent(file);
        const loaded = text ?? '';
        setContent(loaded);
        setOriginalContent(loaded);
        setLoading(false);
    }, []);

    useEffect(() => {
        loadFile(activeFile);
    }, [activeFile, loadFile]);

    const handleSave = async () => {
        setSaving(true);
        setSaveError(null);
        const ok = await saveFileContent(activeFile, content);
        if (ok) {
            setOriginalContent(content);
        } else {
            setSaveError('Save failed. Check backend connection.');
        }
        setSaving(false);
    };

    const handleKeyDown = (e: React.KeyboardEvent) => {
        if ((e.metaKey || e.ctrlKey) && e.key === 's') {
            e.preventDefault();
            handleSave();
        }
        if (e.key === 'Escape' && !isDirty) {
            onClose();
        }
    };

    const insertTemplate = (tpl: string) => {
        setContent(prev => (prev ? prev + '\n\n' + tpl : tpl));
        setShowTemplates(false);
    };

    return (
        <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
            onClick={e => { if (e.target === e.currentTarget) onClose(); }}
        >
            <div
                className="relative flex flex-col w-full max-w-3xl h-[80vh] max-h-[800px]
                    bg-[var(--bg-primary)] border border-[var(--border)] rounded-xl shadow-2xl overflow-hidden"
                onKeyDown={handleKeyDown}
            >
                {/* Header */}
                <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--border)] bg-[var(--bg-secondary)] shrink-0">
                    <div className="flex items-center gap-2">
                        <FileText size={15} className="text-[var(--accent)]" />
                        <span className="text-sm font-semibold text-[var(--text-primary)]">Memory Files</span>
                        {isDirty && <span className="text-xs text-amber-400">● unsaved</span>}
                    </div>
                    <button
                        onClick={onClose}
                        className="p-1.5 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                    >
                        <X size={16} />
                    </button>
                </div>

                {/* File Tabs */}
                <div className="flex items-center gap-1 px-3 py-1.5 border-b border-[var(--border)] bg-[var(--bg-secondary)] shrink-0">
                    {MEMORY_FILES.map(file => (
                        <button
                            key={file}
                            onClick={() => setActiveFile(file)}
                            className={`px-3 py-1 rounded text-xs font-medium transition-colors
                                ${activeFile === file
                                    ? 'bg-[var(--accent)]/15 text-[var(--accent)]'
                                    : 'text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)]'
                                }`}
                        >
                            {file}
                        </button>
                    ))}
                    <div className="flex-1" />
                    {/* Toolbar */}
                    <div className="flex items-center gap-1">
                        <div className="relative">
                            <button
                                onClick={() => setShowTemplates(t => !t)}
                                className="px-2 py-1 rounded text-xs text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)] transition-colors"
                                title="Insert template"
                            >
                                + Template
                            </button>
                            {showTemplates && (
                                <div className="absolute right-0 top-full mt-1 z-10 min-w-[160px]
                                    bg-[var(--bg-secondary)] border border-[var(--border)] rounded-lg shadow-lg overflow-hidden">
                                    {Object.entries(TEMPLATES).map(([name, tpl]) => (
                                        <button
                                            key={name}
                                            onClick={() => insertTemplate(tpl)}
                                            className="block w-full text-left px-3 py-2 text-xs
                                                hover:bg-[var(--bg-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
                                        >
                                            {name}
                                        </button>
                                    ))}
                                </div>
                            )}
                        </div>
                        <button
                            onClick={() => loadFile(activeFile)}
                            className="p-1.5 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                            title="Reload from disk"
                            disabled={loading}
                        >
                            <RefreshCw size={13} className={loading ? 'animate-spin' : ''} />
                        </button>
                        <button
                            onClick={() => setIsPreview(p => !p)}
                            className={`p-1.5 rounded transition-colors
                                ${isPreview
                                    ? 'bg-[var(--accent)]/15 text-[var(--accent)]'
                                    : 'hover:bg-[var(--bg-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)]'
                                }`}
                            title={isPreview ? 'Switch to editor' : 'Switch to preview'}
                        >
                            {isPreview ? <Edit3 size={13} /> : <Eye size={13} />}
                        </button>
                        <button
                            onClick={handleSave}
                            disabled={!isDirty || saving}
                            className="flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-medium
                                bg-[var(--accent)] text-white hover:opacity-90 transition-opacity
                                disabled:opacity-40 disabled:cursor-not-allowed"
                            title="Save (Cmd+S)"
                        >
                            <Save size={12} />
                            {saving ? 'Saving…' : 'Save'}
                        </button>
                    </div>
                </div>

                {/* Error bar */}
                {saveError && (
                    <div className="px-4 py-2 bg-red-500/10 border-b border-red-500/30 text-xs text-red-400 shrink-0">
                        {saveError}
                    </div>
                )}

                {/* Content Area */}
                <div className="flex-1 overflow-hidden">
                    {loading ? (
                        <div className="flex items-center justify-center h-full text-sm text-[var(--text-muted)]">
                            Loading…
                        </div>
                    ) : isPreview ? (
                        <div className="h-full overflow-y-auto px-6 py-4 prose prose-sm prose-invert max-w-none
                            text-[var(--text-primary)] [&_h1]:text-[var(--text-primary)] [&_h2]:text-[var(--text-primary)]
                            [&_h3]:text-[var(--text-primary)] [&_code]:text-[var(--accent)] [&_a]:text-[var(--accent)]">
                            {content
                                ? <LazyMarkdown>{content}</LazyMarkdown>
                                : <p className="text-[var(--text-muted)] italic">Empty file. Switch to editor to add content.</p>
                            }
                        </div>
                    ) : (
                        <textarea
                            className="w-full h-full px-4 py-4 bg-transparent text-sm font-mono text-[var(--text-primary)]
                                resize-none outline-none leading-relaxed"
                            value={content}
                            onChange={e => setContent(e.target.value)}
                            placeholder={`# ${activeFile}\n\nAdd instructions for the AI agent here…`}
                            spellCheck={false}
                        />
                    )}
                </div>

                {/* Footer */}
                <div className="flex items-center justify-between px-4 py-2 border-t border-[var(--border)] bg-[var(--bg-secondary)] shrink-0 text-[10px] text-[var(--text-muted)]">
                    <span>{content.split('\n').length} lines · {content.length} chars</span>
                    <span>Cmd+S save · Esc close</span>
                </div>
            </div>
        </div>
    );
}
