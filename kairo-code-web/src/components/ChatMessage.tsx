import { useSyncExternalStore, useState } from 'react';
import { LazyMarkdown } from './LazyMarkdown';
import { CodeBlock } from './CodeBlock';
import { Copy, Check, RefreshCw, Pencil, Brain, ChevronDown, ChevronUp, Star } from 'lucide-react';
import type { Message } from '@/types/agent';
import { ToolCallCard } from './ToolCallCard';
import { ToolCallGroup } from './ToolCallGroup';
import { DiffBlock } from './DiffBlock';
import { ErrorMessage } from './ErrorMessage';
import { MarkdownTableView } from './MarkdownTableView';
import { inferErrorType } from '@utils/errorType';
import { parseMarkdownContent } from '@utils/markdownTable';
import { streamingStore } from '@store/streamingStore';
import { formatRelativeTime, formatAbsoluteTime } from '@utils/formatTime';
import { useDebounce } from '@hooks/useDebounce';
import { getPreviewContent, countLines, COLLAPSE_PREVIEW_LINES } from '@utils/messageCollapse';
import { MessageReaction } from './MessageReaction';

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
    isBookmarked?: boolean;
    onToggleBookmark?: (messageId: string) => void;
    isCollapsed?: boolean;
    onToggleCollapse?: () => void;
    onRunCommand?: (cmd: string) => void;
    onOpenFile?: (path: string) => void;
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

function renderWithTables(content: string, mkComponents: React.ComponentProps<typeof LazyMarkdown>['components']) {
    const segs = parseMarkdownContent(content);
    return segs.map((seg, i) =>
        seg.type === 'table'
            ? <MarkdownTableView key={i} table={seg.table} />
            : <LazyMarkdown key={i} components={mkComponents}>{seg.content}</LazyMarkdown>
    );
}

export function ChatMessage({ message, onApproveTool, isStreaming, sessionId, onRegenerate, onEditResend, onInsertToChat, onApplyToFile, onRetry, searchHighlight, isCurrentMatch, isBookmarked, onToggleBookmark, isCollapsed, onToggleCollapse, onRunCommand, onOpenFile }: ChatMessageProps) {
    const [copiedMsg, setCopiedMsg] = useState(false);
    const [editing, setEditing] = useState(false);
    const [editText, setEditText] = useState(message.content);

    // Use external streaming store for active streaming messages
    const streamingContent = useSyncExternalStore(
        streamingStore.subscribe,
        () => (sessionId ? streamingStore.getContent(sessionId) : ''),
    );

    // Determine raw content source: streaming store or stored message
    const rawContent = isStreaming && sessionId ? streamingContent : message.content;

    // Apply collapse preview before debouncing
    const displayContent = isCollapsed
        ? getPreviewContent(rawContent ?? '')
        : (rawContent ?? '');

    // Debounce streaming content to avoid per-token ReactMarkdown re-parse (CPU spike)
    const debouncedContent = useDebounce(displayContent, isStreaming ? 80 : 0);

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
        <div className="flex justify-start mb-4 animate-slide-up group">
            <div className="max-w-[85%]">
                <div className={`relative px-4 py-2.5 rounded-2xl rounded-bl-sm bg-[var(--bg-secondary)] border border-[var(--border)] ${searchHighlight ? 'ring-1 ring-[var(--accent)]/40' : ''} ${isCurrentMatch ? 'ring-2 ring-[var(--accent)]' : ''}`}>
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
                        {onToggleBookmark && (
                            <button
                                onClick={() => onToggleBookmark(message.id)}
                                className={`flex items-center gap-1 rounded px-1.5 py-1 hover:bg-[var(--bg-tertiary)] transition-colors ${
                                    isBookmarked
                                        ? 'text-amber-400 hover:text-amber-300'
                                        : 'text-[var(--text-muted)] hover:text-amber-400'
                                }`}
                                title={isBookmarked ? 'Remove bookmark' : 'Bookmark message'}
                            >
                                <Star size={14} fill={isBookmarked ? 'currentColor' : 'none'} />
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
                        {message.id && <MessageReaction messageId={message.id} />}
                    </div>

                    {thinkBlocks.map((t, i) => <ThinkBlock key={i} content={t} />)}

                    {hasContent && (
                        <div className="prose prose-sm dark:prose-invert max-w-none">
                            {renderWithTables(mainContent, {
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
                                                onRun={onRunCommand}
                                            />
                                        );
                                    }
                                    // Inline code — check if it looks like a file path
                                    if (propsRecord.inline) {
                                        const text = String(children);
                                        const filePathRegex = /^\.?\/?([\w.-]+\/)*[\w.-]+\.[a-zA-Z]{1,10}$/;
                                        if (filePathRegex.test(text)) {
                                            return (
                                                <code className={className} {...rest}>
                                                    <a
                                                        className="file-link"
                                                        data-path={text}
                                                        onClick={(e) => {
                                                            e.preventDefault();
                                                            onOpenFile?.(text);
                                                        }}
                                                        style={{
                                                            color: 'var(--accent)',
                                                            textDecoration: 'underline',
                                                            cursor: 'pointer',
                                                        }}
                                                    >
                                                        {children}
                                                    </a>
                                                </code>
                                            );
                                        }
                                    }
                                    return (
                                        <code className={className} {...rest}>
                                            {children}
                                        </code>
                                    );
                                },
                            })}
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

                    {/* Collapse/expand toggle */}
                    {onToggleCollapse && (
                        <button
                            onClick={onToggleCollapse}
                            className="mt-2 flex items-center gap-1.5 text-xs text-[var(--accent)] hover:text-[var(--accent)]/80 transition-colors"
                        >
                            {isCollapsed ? (
                                <>
                                    <ChevronDown size={13} />
                                    {`Show ${Math.max(0, countLines(message.content ?? '') - COLLAPSE_PREVIEW_LINES)} more lines`}
                                </>
                            ) : (
                                <>
                                    <ChevronUp size={13} />
                                    Show less
                                </>
                            )}
                        </button>
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
