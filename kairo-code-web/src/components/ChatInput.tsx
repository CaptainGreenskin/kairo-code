import { useState, useRef, useEffect } from 'react';
import { Send, Square } from 'lucide-react';

interface ChatInputProps {
    onSend: (text: string) => void;
    onStop: () => void;
    disabled: boolean;
    isThinking: boolean;
}

export function ChatInput({ onSend, onStop, disabled, isThinking }: ChatInputProps) {
    const [text, setText] = useState('');
    const textareaRef = useRef<HTMLTextAreaElement>(null);

    useEffect(() => {
        textareaRef.current?.focus();
    }, []);

    const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            handleSend();
        }
    };

    const handleSend = () => {
        const trimmed = text.trim();
        if (!trimmed || disabled) return;
        onSend(trimmed);
        setText('');
    };

    return (
        <div className="border-t border-[var(--border)] bg-[var(--bg-secondary)] p-4">
            <div className="max-w-3xl mx-auto flex items-end gap-2">
                <textarea
                    ref={textareaRef}
                    value={text}
                    onChange={(e) => setText(e.target.value)}
                    onKeyDown={handleKeyDown}
                    placeholder="Type a message... (Enter to send, Shift+Enter for new line)"
                    disabled={disabled && !isThinking}
                    rows={1}
                    className="flex-1 resize-none rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2.5 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-muted)] focus:outline-none focus:ring-2 focus:ring-[var(--color-focus-ring)] disabled:opacity-50"
                    style={{ maxHeight: '120px', minHeight: '40px' }}
                />

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
