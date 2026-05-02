import { useState } from 'react';
import { Copy, Check, Maximize2, ChevronDown, ChevronUp, Hash, ArrowDownToLine, MessageSquarePlus, Play } from 'lucide-react';
import { LazySyntaxHighlighter } from './LazySyntaxHighlighter';

const COLLAPSE_LINES = 40;

const SHELL_LANGUAGES = ['shell', 'bash', 'sh', 'zsh', 'console'];

interface CodeBlockProps {
    language: string;
    content: string;
    meta?: string;
    onInsertToChat?: (text: string) => void;
    onApplyToFile?: (filename: string, content: string) => void;
    onRun?: (command: string) => void;
}

export function CodeBlock({ language, content, meta, onInsertToChat, onApplyToFile, onRun }: CodeBlockProps) {
    const [copied, setCopied] = useState(false);
    const [showLines, setShowLines] = useState(false);
    const [expanded, setExpanded] = useState(false);
    const [fullscreen, setFullscreen] = useState(false);

    const lines = content.split('\n');
    const displayLines = lines.length > 1 && lines[lines.length - 1] === '' ? lines.slice(0, -1) : lines;
    const lineCount = displayLines.length;
    const isLong = lineCount > COLLAPSE_LINES;
    const displayContent = isLong && !expanded
        ? displayLines.slice(0, COLLAPSE_LINES).join('\n')
        : content;

    // Parse title from meta string (e.g., title="main.py")
    const titleMatch = meta?.match(/title="([^"]+)"/);
    const title = titleMatch?.[1];

    const handleCopy = () => {
        navigator.clipboard.writeText(content).then(() => {
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
        });
    };

    const highlighter = (codeContent: string) => (
        <LazySyntaxHighlighter
            language={language || 'text'}
            PreTag="div"
            customStyle={{
                margin: 0,
                borderRadius: 0,
                borderTop: 'none',
                borderBottom: 'none',
                background: 'transparent',
                padding: showLines ? '1rem 1rem 1rem 0' : '1rem',
            }}
            showLineNumbers={showLines}
            wrapLines
        >
            {codeContent}
        </LazySyntaxHighlighter>
    );

    return (
        <>
            <div className="relative my-3 rounded-lg overflow-hidden border border-[var(--border)]">
                {/* Header bar */}
                <div className="flex items-center justify-between px-3 py-1.5 bg-[var(--bg-secondary)] border-b border-[var(--border)]">
                    <div className="flex items-center gap-2">
                        <span className="text-xs text-[var(--text-muted)] font-mono">{language || 'text'}</span>
                        {title && (
                            <span className="text-xs text-[var(--text-secondary)]">{title}</span>
                        )}
                    </div>
                    <div className="flex items-center gap-1">
                        {onRun && SHELL_LANGUAGES.includes(language) && (
                            <button
                                onClick={() => onRun(content)}
                                className="p-1 rounded text-[var(--text-muted)] hover:text-[var(--accent)] transition-colors"
                                title="Run command"
                            >
                                <Play size={11} />
                            </button>
                        )}
                        {onInsertToChat && (
                            <button
                                onClick={() => onInsertToChat('```' + (language || '') + '\n' + content + '\n```')}
                                className="p-1 rounded text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                                title="Insert to chat"
                            >
                                <MessageSquarePlus size={11} />
                            </button>
                        )}
                        {onApplyToFile && title && (
                            <button
                                onClick={() => onApplyToFile(title, content)}
                                className="p-1 rounded text-[var(--text-muted)] hover:text-[var(--accent)] transition-colors"
                                title={`Apply to ${title}`}
                            >
                                <ArrowDownToLine size={11} />
                            </button>
                        )}
                        <button
                            onClick={() => setShowLines(v => !v)}
                            className={`p-1 rounded transition-colors ${showLines ? 'text-[var(--text-primary)]' : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]'}`}
                            title="Toggle line numbers"
                        >
                            <Hash size={11} />
                        </button>
                        <button
                            onClick={() => setFullscreen(true)}
                            className="p-1 rounded text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                            title="Fullscreen"
                        >
                            <Maximize2 size={11} />
                        </button>
                        <button
                            onClick={handleCopy}
                            className="p-1 rounded text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                            title="Copy"
                        >
                            {copied ? <Check size={11} className="text-emerald-400" /> : <Copy size={11} />}
                        </button>
                    </div>
                </div>

                {/* Code content */}
                <div className={`bg-[var(--color-code-bg)] ${isLong && !expanded ? 'max-h-[480px] overflow-hidden' : ''}`}>
                    {highlighter(displayContent)}
                </div>

                {/* Collapse/expand toggle */}
                {isLong && (
                    <button
                        onClick={() => setExpanded(v => !v)}
                        className="w-full flex items-center justify-center gap-1 py-1.5 text-xs text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-secondary)] border-t border-[var(--border)] transition-colors"
                    >
                        {expanded
                            ? <><ChevronUp size={12} /> Show less</>
                            : <><ChevronDown size={12} /> Show {lineCount - COLLAPSE_LINES} more lines</>
                        }
                    </button>
                )}
            </div>

            {/* Fullscreen modal */}
            {fullscreen && (
                <div
                    className="fixed inset-0 z-50 bg-black/90 flex flex-col"
                    onClick={() => setFullscreen(false)}
                >
                    <div className="flex items-center justify-between px-4 py-2 bg-[#1e1e1e] border-b border-white/10" onClick={e => e.stopPropagation()}>
                        <span className="text-xs font-mono text-white/60">{language || 'text'} · {lineCount} lines</span>
                        <button onClick={() => setFullscreen(false)} className="text-white/40 hover:text-white/80 text-xs">✕ Close</button>
                    </div>
                    <div className="flex-1 overflow-auto p-4" onClick={e => e.stopPropagation()}>
                        {highlighter(content)}
                    </div>
                </div>
            )}
        </>
    );
}
