import { useEffect, useState } from 'react';
import { X, Loader2 } from 'lucide-react';
import { getFileContent } from '@api/config';
import Editor from '@monaco-editor/react';

interface FileEditorPanelProps {
    path: string;
    onClose: () => void;
    onSaved: () => void;
}

export function FileEditorPanel({ path, onClose, onSaved }: FileEditorPanelProps) {
    const [content, setContent] = useState('');
    const [language, setLanguage] = useState('plaintext');
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        setLoading(true);
        setError(null);
        getFileContent(path)
            .then((res) => {
                setContent(res.content);
                setLanguage(res.language);
                setLoading(false);
            })
            .catch((err) => {
                setError(err.message);
                setLoading(false);
            });
    }, [path]);

    const handleSave = async () => {
        try {
            await fetch(`/api/files/${encodeURIComponent(path)}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ content }),
            });
            onSaved();
        } catch {
            setError('Failed to save file');
        }
    };

    return (
        <div className="relative flex flex-col w-[480px] border-l border-[var(--border)] bg-[var(--bg-primary)] h-full flex-shrink-0">
            {/* Header */}
            <div className="flex items-center justify-between px-3 py-2 border-b border-[var(--border)]">
                <div className="flex items-center gap-2 min-w-0">
                    <span className="text-sm font-medium text-[var(--text-primary)] truncate">
                        {path.split('/').pop()}
                    </span>
                    <span className="text-xs text-[var(--text-muted)] truncate">
                        {path}
                    </span>
                </div>
                <div className="flex items-center gap-1">
                    <button
                        onClick={handleSave}
                        className="px-2 py-1 text-xs font-medium rounded bg-[var(--color-primary)] text-white hover:opacity-90 transition-opacity"
                    >
                        Save
                    </button>
                    <button
                        onClick={onClose}
                        className="p-0.5 rounded hover:bg-[var(--bg-secondary)] transition-colors"
                    >
                        <X size={14} className="text-[var(--text-muted)]" />
                    </button>
                </div>
            </div>

            {/* Editor */}
            {loading ? (
                <div className="flex-1 flex items-center justify-center">
                    <Loader2 size={20} className="animate-spin text-[var(--text-muted)]" />
                </div>
            ) : error ? (
                <div className="flex-1 flex items-center justify-center text-sm text-[var(--color-danger)]">
                    {error}
                </div>
            ) : (
                <div className="flex-1">
                    <Editor
                        height="100%"
                        language={language}
                        value={content}
                        onChange={(value) => setContent(value ?? '')}
                        theme="vs-dark"
                        options={{
                            minimap: { enabled: false },
                            fontSize: 13,
                            lineNumbers: 'on',
                            scrollBeyondLastLine: false,
                            automaticLayout: true,
                        }}
                    />
                </div>
            )}
        </div>
    );
}
