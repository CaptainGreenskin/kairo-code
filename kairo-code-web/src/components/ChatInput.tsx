import { useState, useRef, useEffect, useCallback } from 'react';
import { Send, Square } from 'lucide-react';
import { FilePicker } from './FilePicker';

interface ChatInputProps {
    onSend: (text: string) => void;
    onStop: () => void;
    disabled: boolean;
    isThinking: boolean;
    appendText?: string;
    onAppendConsumed?: () => void;
    sentMessages?: string[];
}

const AT_RE = /@([^\s]*)$/;
const MAX_FILE_SIZE = 100_000;

function inferLanguage(fileName: string): string {
    const dot = fileName.lastIndexOf('.');
    if (dot < 0) return '';
    const ext = fileName.substring(dot + 1).toLowerCase();
    const map: Record<string, string> = {
        java: 'java', kt: 'kotlin', kts: 'kotlin', ts: 'typescript', tsx: 'tsx',
        js: 'javascript', jsx: 'jsx', py: 'python', go: 'go', rs: 'rust',
        rb: 'ruby', cs: 'csharp', cpp: 'cpp', cc: 'cpp', cxx: 'cpp',
        c: 'c', h: 'c', scala: 'scala', groovy: 'groovy',
        yaml: 'yaml', yml: 'yaml', json: 'json', xml: 'xml',
        html: 'html', htm: 'html', css: 'css', scss: 'scss',
        sh: 'bash', bash: 'bash', sql: 'sql', md: 'markdown',
        toml: 'toml', properties: 'properties', gradle: 'groovy',
    };
    return map[ext] || '';
}

export function ChatInput({ onSend, onStop, disabled, isThinking, appendText, onAppendConsumed, sentMessages }: ChatInputProps) {
    const [text, setText] = useState('');
    const [dragging, setDragging] = useState(false);
    const [showFilePicker, setShowFilePicker] = useState(false);
    const [atQuery, setAtQuery] = useState('');
    const [historyIndex, setHistoryIndex] = useState<number | null>(null);
    const [draftText, setDraftText] = useState('');
    const textareaRef = useRef<HTMLTextAreaElement>(null);

    useEffect(() => {
        textareaRef.current?.focus();
    }, []);

    // Handle external text append (e.g. from file tree panel)
    useEffect(() => {
        if (appendText) {
            setText(prev => prev + appendText);
            onAppendConsumed?.();
            textareaRef.current?.focus();
        }
    }, [appendText, onAppendConsumed]);

    // Detect @ trigger on text change
    useEffect(() => {
        // Find cursor position
        const ta = textareaRef.current;
        if (!ta) return;
        const cursorPos = ta.selectionStart;
        const textBeforeCursor = text.substring(0, cursorPos);
        const match = textBeforeCursor.match(AT_RE);
        if (match) {
            setAtQuery(match[1]);
            setShowFilePicker(true);
        } else {
            setShowFilePicker(false);
            setAtQuery('');
        }
    }, [text]);

    const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
        // History navigation: ArrowUp
        if (e.key === 'ArrowUp' && !e.shiftKey && (sentMessages?.length ?? 0) > 0) {
            const textarea = e.currentTarget;
            const firstNewline = textarea.value.indexOf('\n');
            const atFirstLine = firstNewline === -1 || textarea.selectionStart <= firstNewline;
            if (!atFirstLine) return;

            e.preventDefault();
            const history = sentMessages!;
            if (historyIndex === null) {
                setDraftText(text);
                const newIndex = history.length - 1;
                setHistoryIndex(newIndex);
                setText(history[newIndex]);
            } else if (historyIndex > 0) {
                const newIndex = historyIndex - 1;
                setHistoryIndex(newIndex);
                setText(history[newIndex]);
            }
            return;
        }

        // History navigation: ArrowDown
        if (e.key === 'ArrowDown' && historyIndex !== null && !e.shiftKey) {
            e.preventDefault();
            const history = sentMessages!;
            if (historyIndex < history.length - 1) {
                const newIndex = historyIndex + 1;
                setHistoryIndex(newIndex);
                setText(history[newIndex]);
            } else {
                setHistoryIndex(null);
                setText(draftText);
            }
            return;
        }

        // Exit history: Escape
        if (e.key === 'Escape' && historyIndex !== null) {
            e.preventDefault();
            setHistoryIndex(null);
            setText(draftText);
            return;
        }

        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            handleSend();
        }
    };

    const handleSend = () => {
        const trimmed = text.trim();
        if (!trimmed || disabled) return;
        setHistoryIndex(null);
        setDraftText('');
        onSend(trimmed);
        setText('');
        setShowFilePicker(false);
    };

    const handleDrop = (e: React.DragEvent) => {
        e.preventDefault();
        setDragging(false);
        const files = Array.from(e.dataTransfer.files);
        files.forEach((file) => {
            if (file.size > MAX_FILE_SIZE) {
                setText((prev) => prev + `\n\n[File too large: ${file.name} (${(file.size / 1024).toFixed(1)}KB > 100KB)]\n`);
                return;
            }
            const reader = new FileReader();
            reader.onload = (ev) => {
                const content = ev.target?.result as string;
                const lang = inferLanguage(file.name);
                const block = `\n\n\`\`\`${lang}\n// ${file.name}\n${content}\n\`\`\`\n`;
                setText((prev) => prev + block);
            };
            reader.readAsText(file);
        });
    };

    const handleDragOver = (e: React.DragEvent) => {
        e.preventDefault();
        setDragging(true);
    };

    const handleDragLeave = (e: React.DragEvent) => {
        e.preventDefault();
        setDragging(false);
    };

    const handleFilePickerSelect = useCallback((block: string) => {
        // Replace the @query with the file content block
        const ta = textareaRef.current;
        if (!ta) {
            setText((prev) => prev + block);
            setShowFilePicker(false);
            return;
        }
        const cursorPos = ta.selectionStart;
        const textBeforeCursor = text.substring(0, cursorPos);
        const match = textBeforeCursor.match(AT_RE);
        if (match) {
            const atStart = cursorPos - match[0].length;
            const newText = text.substring(0, atStart) + block + text.substring(cursorPos);
            setText(newText);
        } else {
            setText((prev) => prev + block);
        }
        setShowFilePicker(false);
    }, [text]);

    const handleFilePickerClose = useCallback(() => {
        setShowFilePicker(false);
    }, []);

    return (
        <div
            className={`border-t border-[var(--border)] bg-[var(--bg-secondary)] p-4 transition-all ${
                dragging ? 'ring-2 ring-[var(--color-primary)]' : ''
            }`}
            onDrop={handleDrop}
            onDragOver={handleDragOver}
            onDragLeave={handleDragLeave}
        >
            <div className="max-w-3xl mx-auto flex items-end gap-2 relative">
                <div className="flex-1 relative">
                    <textarea
                        ref={textareaRef}
                        value={text}
                        onChange={(e) => setText(e.target.value)}
                        onKeyDown={handleKeyDown}
                        placeholder={historyIndex !== null ? '↑↓ browsing history · Esc to cancel' : "Type a message... (Enter to send, Shift+Enter for new line, @ to insert file)"}
                        disabled={disabled && !isThinking}
                        rows={1}
                        className="w-full resize-none rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2.5 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-muted)] focus:outline-none focus:ring-2 focus:ring-[var(--color-focus-ring)] disabled:opacity-50"
                        style={{ maxHeight: '120px', minHeight: '40px' }}
                    />
                    {showFilePicker && (
                        <FilePicker
                            query={atQuery}
                            onSelect={handleFilePickerSelect}
                            onClose={handleFilePickerClose}
                        />
                    )}
                </div>

                {isThinking ? (
                    <button
                        onClick={onStop}
                        className="shrink-0 w-10 h-10 flex items-center justify-center rounded-lg bg-[var(--color-danger)] text-white hover:bg-[var(--color-danger)]/90 transition-colors"
                        aria-label="Stop"
                        title="Stop"
                    >
                        <Square size={16} />
                    </button>
                ) : (
                    <button
                        onClick={handleSend}
                        disabled={disabled || !text.trim()}
                        className="shrink-0 w-10 h-10 flex items-center justify-center rounded-lg bg-[var(--color-primary)] text-white hover:bg-[var(--color-primary-hover)] transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        aria-label="Send"
                        title="Send"
                    >
                        <Send size={16} />
                    </button>
                )}
            </div>
        </div>
    );
}
