import { useSyncExternalStore, useState } from 'react';
import Markdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { vscDarkPlus } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { Copy, Check, RefreshCw, Pencil, ArrowDownToLine, MessageSquarePlus, Brain, ChevronDown } from 'lucide-react';
import type { Message } from '@/types/agent';
import { ToolCallCard } from './ToolCallCard';
import { ToolCallGroup } from './ToolCallGroup';
import { DiffBlock } from './DiffBlock';
import { ErrorMessage } from './ErrorMessage';
import { inferErrorType } from '@utils/errorType';
import { streamingStore } from '@store/streamingStore';
import { formatRelativeTime, formatAbsoluteTime } from '@utils/formatTime';
import { useDebounce } from '@hooks/useDebounce';

interface ChatMessageProps {
    message: Message;
    onApproveTool?: (toolCallId: string, approved: boolean) => void;
    isStreaming?: boolean;
    sessionId?: string;
    onRegenerate?: (messageId: string) => void;
    onEditResend?: (messageId: string, newText: string) => void;
    onInsertToChat?: (text: string) => void;
    onApplyToFile?: (filename: string, content: string) => void;
    onRetry?: () => void;
    searchHighlight?: boolean;
    isCurrentMatch?: boolean;
}

interface CodeBlockProps {
    language: string;
    content: string;
    meta?: string;
    onInsertToChat?: (text: string) => void;
    onApplyToFile?: (filename: string, content: string) => void;
    onRetry?: () => void;
}

function CodeBlock({ language, content, meta, onInsertToChat, onApplyToFile }: CodeBlockProps) {
    const [copied, setCopied] = useState(false);

    const handleCopy = () => {
        navigator.clipboard.writeText(content).then(() => {
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
        });
    };

    // Parse title from meta string (e.g., title="main.py")
    const titleMatch = meta?.match(/title="([^"]+)"/);
    const title = titleMatch?.[1];

    const lines = content.split('\n');
    // Remove trailing empty line if content ends with newline
    const displayLines = lines.length > 1 && lines[lines.length - 1] === '' ? lines.slice(0, -1) : lines;

    return (
        <div className="relative group rounded-lg overflow-hidden border border-[var(--border)] my-3">
            {/* Header bar: language tag + title + copy button */}
            <div className="flex items-center justify-between px-3 py-1.5 bg-[var(--bg-secondary)] border-b border-[var(--border)]">
                <div className="flex items-center gap-2">
                    {language && (
                        <span className="text-xs text-[var(--text-muted)] font-mono">{language}</span>
                    )}
                    {title && (
                        <span className="text-xs text-[var(--text-secondary)]">{title}</span>
                    )}
                </div>
                <div className="flex items-center gap-3">
                    {onInsertToChat && (
                        <button
                            onClick={() => onInsertToChat('```' + (language || '') + '\n' + content + '\n```')}
                            className="flex items-center gap-1 text-xs text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                            title="Insert to chat"
                        >
                            <MessageSquarePlus size={12} />
                        </button>
                    )}
                    {onApplyToFile && title && (
                        <button
                            onClick={() => onApplyToFile(title, content)}
                            className="flex items-center gap-1 text-xs text-[var(--text-muted)] hover:text-[var(--accent)] transition-colors"
                            title={`Apply to ${title}`}
                        >
                            <ArrowDownToLine size={12} />
                            <span>Apply</span>
                        </button>
                    )}
                    <button
                        onClick={handleCopy}
                        className="flex items-center gap-1 text-xs text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                    >
                        {copied ? <Check size={12} /> : <Copy size={12} />}
                        <span>{copied ? 'Copied' : 'Copy'}</span>
                    </button>
                </div>
            </div>
            {/* Code with line numbers */}
            <div className="flex overflow-x-auto bg-[var(--color-code-bg)]">
                {/* Line numbers column */}
                <pre className="flex-shrink-0 m-0 py-4 pl-4 pr-4 text-right text-xs text-[var(--text-muted)] select-none font-mono leading-[1.5]">
                    {displayLines.map((_, i) => (
                        <span key={i} className="block">{i + 1}</span>
                    ))}
                </pre>
                {/* Syntax highlighted code */}
                <div className="flex-1 min-w-0">
                    <SyntaxHighlighter
                        style={vscDarkPlus as never}
                        language={language}
                        PreTag="div"
                        customStyle={{
                            margin: 0,
                            borderRadius: 0,
                            borderTop: 'none',
                            borderBottom: 'none',
                            background: 'transparent',
                            padding: '1rem 1rem 1rem 0',
                        }}
                        showLineNumbers={false}
                        wrapLines={false}
                    >
                        {content}
                    </SyntaxHighlighter>
                </div>
            </div>
        </div>
    );
}

function extractThinkBlocks(content: string): { think: string[]; rest: string } {
    const thinkPattern = /<think>([\s\S]*?)<\/think>/g;
    const thinks: string[] = [];
    const rest = content.replace(thinkPattern, (_, inner) => {
        thinks.push(inner.trim());
        return '';
    }).trim();
    return { think: thinks, rest };
}

function ThinkBlock({ content }: { content: string }) {
    const [open, setOpen] = useState(false);
    return (
        <div className="my-2 rounded border border-[var(--border)] bg-[var(--bg-secondary)]">
            <button
                onClick={() => setOpen(v => !v)}
                className="flex w-full items-center gap-2 px-3 py-2 text-xs text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
            >
                <Brain size={12} />
                <span>Reasoning</span>
                <ChevronDown
                    size={12}
                    className={`ml-auto transition-transform ${open ? 'rotate-180' : ''}`}
                />
            </button>
            {open && (
                <div className="px-3 pb-3 text-xs text-[var(--text-secondary)] whitespace-pre-wrap border-t border-[var(--border)] pt-2">
                    {content}
                </div>
            )}
        </div>
    );
}

export function ChatMessage({ message, onApproveTool, isStreaming, sessionId, onRegenerate, onEditResend, onInsertToChat, onApplyToFile, onRetry, searchHighlight, isCurrentMatch }: ChatMessageProps) {
    const [copiedMsg, setCopiedMsg] = useState(false);
    const [editing, setEditing] = useState(false);
    const [editText, setEditText] = useState(message.content);

    // Use external streaming store for active streaming messages
    const streamingContent = useSyncExternalStore(
        streamingStore.subscribe,
        () => (sessionId ? streamingStore.getContent(sessionId) : ''),
    );

    const displayContent = isStreaming && sessionId ? streamingContent : message.content;

    // Debounce streaming content to avoid per-token ReactMarkdown re-parse (CPU spike)
    const debouncedContent = useDebounce(displayContent ?? '', isStreaming ? 80 : 0);

    if (message.role === 'user') {
        return (
            <div className="flex justify-end mb-4 animate-slide-up group">
                <div className="max-w-[80%] px-4 py-2.5 rounded-2xl rounded-br-sm bg-[var(--color-primary)] text-white">
                    {editing ? (
                        <div>
                            <textarea
                                className="w-full p-2 text-sm bg-[var(--color-primary)] border border-white/30 rounded-lg resize-none outline-none text-white placeholder-white/50"
                                value={editText}
                                onChange={e => setEditText(e.target.value)}
                                onKeyDown={e => {
                                    if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
                                        e.preventDefault();
                                        if (editText.trim()) {
                                            onEditResend?.(message.id, editText.trim());
                                            setEditing(false);
                                        }
                                    }
                                    if (e.key === 'Escape') {
                                        setEditing(false);
                                        setEditText(message.content);
                                    }
                                }}
                                rows={Math.min(editText.split('\n').length + 1, 8)}
                                autoFocus
                            />
                            <div className="flex gap-2 mt-1">
                                <button
                                    onClick={() => {
                                        if (editText.trim()) {
                                            onEditResend?.(message.id, editText.trim());
                                            setEditing(false);
                                        }
                                    }}
                                    className="px-3 py-1 text-xs font-medium text-[var(--color-primary)] bg-white rounded hover:bg-white/90 transition-colors"
                                >
                                    Resend
                                </button>
                                <button
                                    onClick={() => { setEditing(false); setEditText(message.content); }}
                                    className="px-3 py-1 text-xs font-medium text-white/80 rounded hover:bg-white/10 transition-colors"
                                >
                                    Cancel
                                </button>
                            </div>
                            <div className="text-[10px] text-white/50 mt-0.5">⌘↵ to send · Esc to cancel</div>
                        </div>
                    ) : (
                        <>
                            <p className="whitespace-pre-wrap text-sm">{message.content}</p>
                            <div className="flex items-center justify-between mt-1">
                                <span className="text-[10px] opacity-60">
                                    {message.timestamp && (
                                        <span
                                            className="opacity-0 group-hover:opacity-100 transition-opacity"
                                            title={formatAbsoluteTime(message.timestamp)}
                                        >
                                            {formatRelativeTime(message.timestamp)}
                                        </span>
                                    )}
                                </span>
                                {onEditResend && !isStreaming && (
                                    <button
                                        onClick={() => setEditing(true)}
                                        title="Edit message"
                                        className="p-1 rounded hover:bg-white/10 text-white/60 hover:text-white transition-colors"
                                    >
                                        <Pencil size={14} />
                                    </button>
                                )}
                            </div>
                        </>
                    )}
                </div>
            </div>
        );
    }


    if (message.role === 'error') {
        return (
            <ErrorMessage
                message={message.content}
                errorType={inferErrorType(message.content)}
                onRetry={onRetry}
            />
        );
    }

    const hasToolCalls = message.toolCalls.length > 0;
    const { think: thinkBlocks, rest: mainContent } = extractThinkBlocks(debouncedContent);
    const hasContent = mainContent.length > 0;

    const handleCopyMessage = () => {
        navigator.clipboard.writeText(message.content).then(() => {
            setCopiedMsg(true);
            setTimeout(() => setCopiedMsg(false), 2000);
        });
    };

    return (
        <div className="flex justify-start mb-4 animate-slide-up">
            <div className="max-w-[85%]">
                <div className={`relative group px-4 py-2.5 rounded-2xl rounded-bl-sm bg-[var(--bg-secondary)] border border-[var(--border)] ${searchHighlight ? 'ring-1 ring-[var(--accent)]/40' : ''} ${isCurrentMatch ? 'ring-2 ring-[var(--accent)]' : ''}`}>
                    {/* Right-side overlay: timestamp + action buttons */}
                    <div className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity flex flex-col items-end gap-1">
                        {/* Timestamp tooltip */}
                        {message.timestamp && (
                            <span className="text-[10px] text-[var(--text-muted)] select-none">
                                {formatAbsoluteTime(message.timestamp)}
                            </span>
                        )}
                        {/* Message-level action buttons */}
                        <div className="flex gap-1">
                        {onRegenerate && !isStreaming && (
                            <button
                                onClick={() => onRegenerate(message.id)}
                                className="flex items-center gap-1 rounded px-1.5 py-1 hover:bg-[var(--bg-tertiary)] text-[var(--text-muted)] hover:text-[var(--text-primary)]"
                                title="Regenerate response"
                            >
                                <RefreshCw size={14} />
                            </button>
                        )}
                        <button
                            onClick={handleCopyMessage}
                            className="flex items-center gap-1 rounded px-1.5 py-1 hover:bg-[var(--bg-tertiary)] text-[var(--text-muted)] hover:text-[var(--text-primary)]"
                            title="Copy message"
                        >
                            {copiedMsg ? <Check size={14} /> : <Copy size={14} />}
                        </button>
                        </div>
                    </div>

                    {thinkBlocks.map((t, i) => <ThinkBlock key={i} content={t} />)}

                    {hasContent && (
                        <div className="prose prose-sm dark:prose-invert max-w-none">
                            <Markdown
                                components={{
                                    code(props) {
                                        const { className, children, ...rest } = props as {
                                            className?: string;
                                            children: React.ReactNode;
                                        } & Record<string, unknown>;
                                        const propsRecord = props as Record<string, unknown>;
                                        const match = /language-(\w+)/.exec(className || '');
                                        const lang = match ? match[1] : '';
                                        const content = String(children).replace(/\n$/, '');
                                        if (!propsRecord.inline && content) {
                                            // Detect unified diff: explicit diff/patch lang or --- + +++ markers
                                            if (lang === 'diff' || lang === 'patch' ||
                                                (content.includes('\n--- ') && content.includes('\n+++ '))) {
                                                return <DiffBlock content={content} />;
                                            }
                                            return (
                                                <CodeBlock
                                                    language={lang}
                                                    content={content}
                                                    meta={propsRecord.meta as string | undefined}
                                                    onInsertToChat={onInsertToChat}
                                                    onApplyToFile={onApplyToFile}
                                                />
                                            );
                                        }
                                        return (
                                            <code className={className} {...rest}>
                                                {children}
                                            </code>
                                        );
                                    },
                                }}
                            >
                                {mainContent}
                            </Markdown>
                        </div>
                    )}

                    {isStreaming && (
                        <span
                            className="typing-cursor"
                        />
                    )}

                    {hasToolCalls && (
                        message.toolCalls.length === 1 ? (
                            <div className="mt-2">
                                <ToolCallCard
                                    toolCall={message.toolCalls[0]}
                                    onApprove={onApproveTool}
                                />
                            </div>
                        ) : (
                            <ToolCallGroup
                                toolCalls={message.toolCalls}
                                onApprove={onApproveTool}
                                isStreaming={isStreaming}
                            />
                        )
                    )}

                    {/* Character count */}
                    {!isStreaming && message.role === 'assistant' && message.content.length > 0 && (
                        <div className="text-[10px] text-[var(--text-muted)] mt-1 text-right">
                            {message.content.length.toLocaleString()} chars
                        </div>
                    )}
                </div>

                <span className="text-[10px] text-[var(--text-muted)] mt-1 ml-2 block opacity-0 group-hover:opacity-100 transition-opacity" title={message.timestamp ? formatAbsoluteTime(message.timestamp) : undefined}>
                    {message.timestamp ? formatRelativeTime(message.timestamp) : ''}
                </span>
            </div>
        </div>
    );
}
