import { useEffect, useRef, useState } from 'react';
import { X, Loader2, Eye, Pencil, ChevronRight, FileWarning } from 'lucide-react';
import { getFileContent, putFileContent } from '@api/config';
import * as monaco from 'monaco-editor';
import { LazyMarkdown } from './LazyMarkdown';

/** Binary/uneditable file basenames the editor will refuse to open with a friendly
 *  message rather than firing a doomed UTF-8 read that surfaces as a raw HTTP 400. */
const KNOWN_BINARY_NAMES = new Set(['.DS_Store', 'Thumbs.db', 'desktop.ini']);
const KNOWN_BINARY_EXTS = new Set([
    'png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'ico', 'tiff', 'icns',
    'pdf', 'zip', 'tar', 'gz', 'tgz', 'bz2', '7z', 'rar', 'jar', 'war',
    'class', 'so', 'dll', 'dylib', 'exe', 'bin', 'o', 'a',
    'mp3', 'mp4', 'mov', 'avi', 'wav', 'ogg', 'webm', 'flac',
    'woff', 'woff2', 'ttf', 'otf', 'eot',
]);

function isLikelyBinary(path: string): boolean {
    const name = path.split('/').pop() ?? path;
    if (KNOWN_BINARY_NAMES.has(name)) return true;
    const dot = name.lastIndexOf('.');
    if (dot < 0) return false;
    return KNOWN_BINARY_EXTS.has(name.substring(dot + 1).toLowerCase());
}

interface FileEditorPanelProps {
    path: string;
    onClose: () => void;
    onSaved: () => void;
    workspaceId?: string;
    /** When set, scroll to and highlight this 1-based line on open + on prop change. */
    gotoLine?: number;
    /** When true, render as a full-width tab body inside an editor area (no fixed
     *  width / left border / X button). Tab bar handles close. */
    embedded?: boolean;
}

export function FileEditorPanel({ path, onClose, onSaved, workspaceId, gotoLine, embedded = false }: FileEditorPanelProps) {
    const [content, setContent] = useState('');
    const [savedContent, setSavedContent] = useState('');
    const [language, setLanguage] = useState('plaintext');
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [viewMode, setViewMode] = useState<'edit' | 'preview'>('edit');
    const [binary, setBinary] = useState(false);
    const [oversize, setOversize] = useState<string | null>(null);
    const isMarkdown = language === 'markdown' || /\.(md|markdown|mdx)$/i.test(path);

    useEffect(() => {
        setError(null);
        setOversize(null);
        if (isLikelyBinary(path)) {
            setBinary(true);
            setLoading(false);
            setContent('');
            setSavedContent('');
            return;
        }
        setBinary(false);
        setLoading(true);
        getFileContent(path, workspaceId)
            .then((res) => {
                setContent(res.content);
                setSavedContent(res.content);
                setLanguage(res.language);
                const md = res.language === 'markdown' || /\.(md|markdown|mdx)$/i.test(path);
                setViewMode(md ? 'preview' : 'edit');
                setLoading(false);
            })
            .catch((err) => {
                const msg: string = err?.message ?? 'Failed to load file';
                // Server returns 400 with "not a valid UTF-8 text file" for binaries we
                // didn't catch via extension heuristics — fall through to the binary UI.
                if (/UTF-?8/i.test(msg) || /binary/i.test(msg)) {
                    setBinary(true);
                    setError(null);
                } else if (/HTTP\s*413/i.test(msg) || /Payload Too Large/i.test(msg) || /File too large/i.test(msg)) {
                    // Try to pull the human-readable size out of the server JSON body.
                    const match = msg.match(/File too large:\s*([^"}\\]+?)\s*(?:exceeds[^"}]*)?(?:["}]|$)/i);
                    setOversize(match ? match[1].trim() : 'File too large to preview');
                    setError(null);
                } else {
                    setError(msg);
                }
                setLoading(false);
            });
    }, [path, workspaceId]);

    const dirty = content !== savedContent;

    const handleSave = async () => {
        if (!dirty || saving) return;
        setSaving(true);
        setError(null);
        try {
            await putFileContent(path, content, workspaceId);
            setSavedContent(content);
            onSaved();
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed to save file');
        } finally {
            setSaving(false);
        }
    };

    const handleSaveRef = useRef(handleSave);
    handleSaveRef.current = handleSave;

    const editorContainerRef = useRef<HTMLDivElement | null>(null);
    const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
    const modelRef = useRef<monaco.editor.ITextModel | null>(null);

    // Mount editor when container is ready and content has loaded.
    useEffect(() => {
        if (loading || binary || oversize) return;
        if (isMarkdown && viewMode === 'preview') return;
        if (!editorContainerRef.current) return;
        if (editorRef.current) return;  // already mounted

        const model = monaco.editor.createModel(content, language);
        modelRef.current = model;

        const editor = monaco.editor.create(editorContainerRef.current, {
            model,
            theme: 'vs-dark',
            minimap: { enabled: false },
            fontSize: 13,
            lineNumbers: 'on',
            scrollBeyondLastLine: false,
            automaticLayout: true,
        });
        editorRef.current = editor;

        const sub = model.onDidChangeContent(() => {
            setContent(model.getValue());
        });

        // ⌘S / Ctrl+S → save
        editor.addCommand(
            monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS,
            () => handleSaveRef.current(),
        );

        if (gotoLine != null) {
            editor.revealLineInCenter(gotoLine);
            editor.setPosition({ lineNumber: gotoLine, column: 1 });
        }

        return () => {
            sub.dispose();
            editor.dispose();
            model.dispose();
            editorRef.current = null;
            modelRef.current = null;
        };
        // Only depend on loading/binary/oversize/viewMode/language transitions that
        // require a remount. `content` changes are pushed via model.setValue below.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [loading, binary, oversize, isMarkdown, viewMode, language]);

    // External content updates (initial load, gotoLine refresh) → push into model.
    useEffect(() => {
        const model = modelRef.current;
        if (!model) return;
        if (model.getValue() !== content) {
            model.setValue(content);
        }
    }, [content]);

    // Reveal the requested line whenever it changes.
    useEffect(() => {
        if (gotoLine == null || !editorRef.current || loading) return;
        const editor = editorRef.current;
        editor.revealLineInCenter(gotoLine);
        editor.setPosition({ lineNumber: gotoLine, column: 1 });
        editor.focus();
        const collection = editor.createDecorationsCollection([{
            range: new monaco.Range(gotoLine, 1, gotoLine, 1),
            options: {
                isWholeLine: true,
                className: 'bg-[var(--color-primary)]/15',
                marginClassName: 'bg-[var(--color-primary)]',
            },
        }]);
        const t = setTimeout(() => collection.clear(), 1500);
        return () => {
            clearTimeout(t);
            collection.clear();
        };
    }, [gotoLine, loading]);

    return (
        <div className={
            embedded
                ? 'relative flex flex-col w-full h-full bg-[var(--bg-primary)]'
                : 'relative flex flex-col w-[480px] border-l border-[var(--border)] bg-[var(--bg-primary)] h-full flex-shrink-0'
        }>
            {/* Header */}
            <div className="flex items-center justify-between px-3 py-2 border-b border-[var(--border)] gap-2">
                <div className="flex items-center gap-1 min-w-0 flex-1 overflow-hidden text-xs text-[var(--text-muted)]" title={path}>
                    {(() => {
                        const segs = path.split('/').filter(Boolean);
                        return segs.map((seg, i) => {
                            const isLast = i === segs.length - 1;
                            return (
                                <span key={i} className="flex items-center gap-1 min-w-0">
                                    <span className={isLast ? 'text-[var(--text-primary)] font-medium truncate' : 'truncate'}>
                                        {seg}
                                    </span>
                                    {!isLast && <ChevronRight size={11} className="shrink-0 opacity-60" />}
                                    {isLast && dirty && <span className="ml-0.5 text-[var(--color-primary)]">●</span>}
                                </span>
                            );
                        });
                    })()}
                </div>
                <div className="flex items-center gap-1">
                    {isMarkdown && !binary && !oversize && (
                        <button
                            onClick={() => setViewMode((m) => (m === 'preview' ? 'edit' : 'preview'))}
                            title={viewMode === 'preview' ? 'Edit source' : 'Preview rendered'}
                            className="p-1 rounded text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-secondary)] transition-colors"
                        >
                            {viewMode === 'preview' ? <Pencil size={13} /> : <Eye size={13} />}
                        </button>
                    )}
                    {!binary && !oversize && (
                        <button
                            onClick={handleSave}
                            disabled={!dirty || saving}
                            title={dirty ? 'Save (⌘S)' : 'No changes'}
                            className="px-2 py-1 text-xs font-medium rounded bg-[var(--color-primary)] text-white hover:opacity-90 transition-opacity disabled:opacity-40 disabled:cursor-not-allowed"
                        >
                            {saving ? 'Saving…' : 'Save'}
                        </button>
                    )}
                    {!embedded && (
                        <button
                            onClick={onClose}
                            className="p-0.5 rounded hover:bg-[var(--bg-secondary)] transition-colors"
                        >
                            <X size={14} className="text-[var(--text-muted)]" />
                        </button>
                    )}
                </div>
            </div>

            {/* Save error banner (does not hide editor) */}
            {error && !loading && (
                <div className="px-3 py-1.5 text-xs text-[var(--color-danger)] bg-[var(--color-danger)]/10 border-b border-[var(--border)] truncate" title={error}>
                    {error}
                </div>
            )}

            {/* Editor */}
            {loading ? (
                <div className="flex-1 flex items-center justify-center">
                    <Loader2 size={20} className="animate-spin text-[var(--text-muted)]" />
                </div>
            ) : binary ? (
                <div className="flex-1 flex flex-col items-center justify-center gap-3 px-6 text-center">
                    <FileWarning size={32} className="text-[var(--text-muted)]" />
                    <div>
                        <div className="text-sm text-[var(--text-primary)]">Binary file — preview not supported</div>
                        <div className="mt-1 text-xs text-[var(--text-muted)] truncate max-w-[420px]" title={path}>{path}</div>
                    </div>
                </div>
            ) : oversize ? (
                <div className="flex-1 flex flex-col items-center justify-center gap-3 px-6 text-center">
                    <FileWarning size={32} className="text-[var(--text-muted)]" />
                    <div>
                        <div className="text-sm text-[var(--text-primary)]">File too large to preview</div>
                        <div className="mt-1 text-xs text-[var(--text-muted)]">{oversize}</div>
                        <div className="mt-1 text-xs text-[var(--text-muted)] truncate max-w-[420px]" title={path}>{path}</div>
                    </div>
                </div>
            ) : isMarkdown && viewMode === 'preview' ? (
                <div className="flex-1 overflow-auto px-5 py-4 prose dark:prose-invert prose-sm max-w-none text-[var(--text-primary)]">
                    <LazyMarkdown>{content}</LazyMarkdown>
                </div>
            ) : (
                <div className="flex-1">
                    <div
                        ref={editorContainerRef}
                        className="w-full h-full"
                        data-testid="monaco-editor-container"
                    />
                </div>
            )}
        </div>
    );
}
