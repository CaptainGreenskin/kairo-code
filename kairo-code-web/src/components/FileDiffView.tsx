import { useState, useCallback } from 'react';
import { Editor, DiffEditor } from '@monaco-editor/react';
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

export function FileDiffView({
    fileName = 'file',
    original,
    modified,
    mode = 'diff',
}: FileDiffViewProps) {
    const [collapsed, setCollapsed] = useState(false);
    const language = detectLanguage(fileName);

    const handleEditorDidMount = useCallback((_editor: unknown) => {
        // Monaco editor auto-mounted
    }, []);

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

                {!collapsed && (
                    <div style={{ height: '300px' }}>
                        <Editor
                            height="100%"
                            language={language}
                            value={modified}
                            options={{
                                readOnly: true,
                                minimap: { enabled: false },
                                scrollBeyondLastLine: false,
                                fontSize: 12,
                                lineNumbers: 'on',
                                wordWrap: 'off',
                            }}
                            onMount={handleEditorDidMount}
                            theme="vs-dark"
                        />
                    </div>
                )}
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

            {!collapsed && (
                <div style={{ height: '400px' }}>
                    <DiffEditor
                        height="100%"
                        original={original}
                        modified={modified}
                        language={language}
                        options={{
                            readOnly: true,
                            minimap: { enabled: false },
                            scrollBeyondLastLine: false,
                            fontSize: 12,
                            renderSideBySide: true,
                        }}
                        onMount={handleEditorDidMount}
                        theme="vs-dark"
                    />
                </div>
            )}
        </div>
    );
}
