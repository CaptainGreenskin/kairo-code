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
const CHAR_WARN_THRESHOLD = 2000;
const CHAR_MAX = 4000;

export function ChatInput({ onSend, onStop, disabled, isThinking, appendText, onAppendConsumed, sentMessages }: ChatInputProps) {
    const [text, setText] = useState('');
    const [dragging, setDragging] = useState(false);
    const [isDragOver, setIsDragOver] = useState(false);
    const [showFilePicker, setShowFilePicker] = useState(false);
    const [atQuery, setAtQuery] = useState('');
    const [historyIndex, setHistoryIndex] = useState<number | null>(null);
    const [draftText, setDraftText] = useState('');
    const textareaRef = useRef<HTMLTextAreaElement>(null);

    useEffect(() => {
        textareaRef.current?.focus();
    }, []);

    // Auto-adjust textarea height
    useEffect(() => {
        const el = textareaRef.current;
        if (!el) return;
        el.style.height = 'auto';
        el.style.height = `${Math.min(el.scrollHeight, 200)}px`;
    }, [text]);

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
        setIsDragOver(false);
        const files = Array.from(e.dataTransfer.files);
        if (files.length === 0) return;
        const paths = files.map(f => f.name).join(' ');
        setText(prev => {
            const textarea = textareaRef.current;
            if (!textarea) return prev + ' ' + paths;
            const start = textarea.selectionStart;
            const end = textarea.selectionEnd;
            return prev.slice(0, start) + paths + prev.slice(end);
        });
    };

    const handleDragOver = (e: React.DragEvent) => {
        e.preventDefault();
        setDragging(true);
        setIsDragOver(true);
    };

    const handleDragLeave = (e: React.DragEvent) => {
        e.preventDefault();
        // Only reset if we actually left the container (not entering a child)
        const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
        const x = e.clientX;
        const y = e.clientY;
        if (x < rect.left || x >= rect.right || y < rect.top || y >= rect.bottom) {
            setDragging(false);
            setIsDragOver(false);
        }
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
                    <div
                        className={`relative transition-colors ${isDragOver ? 'ring-2 ring-[var(--accent)] ring-inset rounded-lg' : ''}`}
                    >
                        <textarea
                            ref={textareaRef}
                            value={text}
                            onChange={(e) => setText(e.target.value)}
                            onKeyDown={handleKeyDown}
                            placeholder={historyIndex !== null ? '↑↓ browsing history · Esc to cancel' : "Type a message... (Enter to send, Shift+Enter for new line, @ to insert file)"}
                            disabled={disabled && !isThinking}
                            rows={1}
                            className="w-full resize-none overflow-y-auto rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2.5 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-muted)] focus:outline-none focus:ring-2 focus:ring-[var(--color-focus-ring)] disabled:opacity-50"
                            style={{ maxHeight: '200px', minHeight: '40px' }}
                        />
                        {text.length > CHAR_WARN_THRESHOLD && (
                            <div className={`absolute bottom-2 right-12 text-[10px] font-mono ${
                                text.length > CHAR_MAX * 0.9
                                    ? 'text-red-400'
                                    : 'text-[var(--text-muted)]'
                            }`}>
                                {text.length}/{CHAR_MAX}
                            </div>
                        )}
                        {isDragOver && (
                            <div className="absolute inset-0 flex items-center justify-center bg-[var(--accent)]/10 rounded-lg z-10 pointer-events-none">
                                <span className="text-sm text-[var(--accent)]">Drop to insert file path</span>
                            </div>
                        )}
                    </div>
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
