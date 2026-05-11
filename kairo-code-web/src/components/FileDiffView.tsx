import { useState, useEffect, useRef } from 'react';
import * as monaco from 'monaco-editor';
import { ChevronDown, ChevronUp, FileText } from 'lucide-react';

const LANG_BY_EXT: Record<string, string> = {
    ts: 'typescript',
    tsx: 'typescriptreact',
    js: 'javascript',
    jsx: 'javascriptreact',
    py: 'python',
    java: 'java',
    go: 'go',
    rs: 'rust',
    rb: 'ruby',
    css: 'css',
    scss: 'scss',
    html: 'html',
    xml: 'xml',
    json: 'json',
    yaml: 'yaml',
    yml: 'yaml',
    md: 'markdown',
    sh: 'shell',
    bash: 'shell',
    sql: 'sql',
    kotlin: 'kotlin',
    kt: 'kotlin',
    c: 'c',
    cpp: 'cpp',
    h: 'c',
    php: 'php',
    swift: 'swift',
};

function detectLanguage(filePath: string): string {
    const ext = filePath.split('.').pop()?.toLowerCase() || '';
    return LANG_BY_EXT[ext] || 'plaintext';
}

interface FileDiffViewProps {
    fileName?: string;
    original: string;
    modified: string;
    mode?: 'diff' | 'preview';
}

/** Inline preview editor (read-only, single file). */
function PreviewPanel({ language, value, collapsed }: { language: string; value: string; collapsed: boolean }) {
    const containerRef = useRef<HTMLDivElement | null>(null);
    const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);

    useEffect(() => {
        if (collapsed) return;
        if (!containerRef.current) return;
        if (editorRef.current) return;

        const model = monaco.editor.createModel(value, language);
        const editor = monaco.editor.create(containerRef.current, {
            model,
            theme: 'vs-dark',
            readOnly: true,
            minimap: { enabled: false },
            scrollBeyondLastLine: false,
            fontSize: 12,
            lineNumbers: 'on',
            wordWrap: 'off',
            automaticLayout: true,
        });
        editorRef.current = editor;

        return () => {
            editor.dispose();
            model.dispose();
            editorRef.current = null;
        };
    }, [collapsed, language, value]);

    if (collapsed) return null;
    return (
        <div style={{ height: '300px' }}>
            <div ref={containerRef} className="w-full h-full" />
        </div>
    );
}

/** Side-by-side diff editor. */
function DiffPanel({ language, original, modified, collapsed }: { language: string; original: string; modified: string; collapsed: boolean }) {
    const containerRef = useRef<HTMLDivElement | null>(null);
    const editorRef = useRef<monaco.editor.IStandaloneDiffEditor | null>(null);

    useEffect(() => {
        if (collapsed) return;
        if (!containerRef.current) return;
        if (editorRef.current) return;

        const originalModel = monaco.editor.createModel(original, language);
        const modifiedModel = monaco.editor.createModel(modified, language);

        const editor = monaco.editor.createDiffEditor(containerRef.current, {
            readOnly: true,
            minimap: { enabled: false },
            scrollBeyondLastLine: false,
            fontSize: 12,
            renderSideBySide: true,
            theme: 'vs-dark',
            automaticLayout: true,
        });
        editor.setModel({ original: originalModel, modified: modifiedModel });
        editorRef.current = editor;

        return () => {
            editor.dispose();
            originalModel.dispose();
            modifiedModel.dispose();
            editorRef.current = null;
        };
    }, [collapsed, language, original, modified]);

    if (collapsed) return null;
    return (
        <div style={{ height: '400px' }}>
            <div ref={containerRef} className="w-full h-full" />
        </div>
    );
}

export function FileDiffView({
    fileName = 'file',
    original,
    modified,
    mode = 'diff',
}: FileDiffViewProps) {
    const [collapsed, setCollapsed] = useState(false);
    const language = detectLanguage(fileName);

    if (mode === 'preview') {
        return (
            <div className="border border-[var(--border)] rounded-lg overflow-hidden bg-[var(--code-bg)]">
                <div className="flex items-center justify-between px-3 py-1.5 border-b border-[var(--border)] bg-[var(--bg-secondary)]">
                    <div className="flex items-center gap-2">
                        <FileText size={14} className="text-[var(--text-muted)]" />
                        <span className="text-xs font-mono text-[var(--text-primary)]">
                            {fileName}
                        </span>
                    </div>
                    <div className="flex items-center gap-1">
                        <button
                            onClick={() => setCollapsed((prev) => !prev)}
                            className="p-1 text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                            title={collapsed ? 'Expand' : 'Collapse'}
                        >
                            {collapsed ? <ChevronDown size={14} /> : <ChevronUp size={14} />}
                        </button>
                    </div>
                </div>

                <PreviewPanel language={language} value={modified} collapsed={collapsed} />
            </div>
        );
    }

    return (
        <div className="border border-[var(--border)] rounded-lg overflow-hidden bg-[var(--code-bg)]">
            <div className="flex items-center justify-between px-3 py-1.5 border-b border-[var(--border)] bg-[var(--bg-secondary)]">
                <div className="flex items-center gap-2">
                    <FileText size={14} className="text-[var(--text-muted)]" />
                    <span className="text-xs font-mono text-[var(--text-primary)]">
                        {fileName}
                    </span>
                </div>
                <button
                    onClick={() => setCollapsed((prev) => !prev)}
                    className="p-1 text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                    title={collapsed ? 'Expand' : 'Collapse'}
                >
                    {collapsed ? <ChevronDown size={14} /> : <ChevronUp size={14} />}
                </button>
            </div>

            <DiffPanel language={language} original={original} modified={modified} collapsed={collapsed} />
        </div>
    );
}
