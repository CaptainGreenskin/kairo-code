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
import { normalizeAssistantMarkdown } from '@utils/markdownNormalize';
import { streamingStore } from '@store/streamingStore';
import { formatRelativeTime, formatAbsoluteTime } from '@utils/formatTime';
import { useDebounce } from '@hooks/useDebounce';
import { getPreviewContent, countLines, COLLAPSE_PREVIEW_LINES } from '@utils/messageCollapse';
import { MessageReaction } from './MessageReaction';
import { ReportLayout, extractHeadings, shouldUseReportLayout } from './ReportLayout';

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

/**
 * Strip `<system-reminder>...</system-reminder>` blocks from user-facing content.
 * The reminders are an agent-only injection (e.g. plan-mode preamble) — surfacing them in
 * the chat bubble is just noise. Mirrors Claude Code's own rendering, which never shows
 * these to the human.
 */
function stripSystemReminders(content: string): string {
    return content.replace(/<system-reminder>[\s\S]*?<\/system-reminder>\s*/g, '').trim();
}

/** Cursor-style thinking label: roughly maps content length to "duration" feel
 *  ("Thought briefly" / "Thought for a moment" / "Thought for a while"). When we
 *  later get a real `durationMs` from the backend, swap this for actual seconds. */
function thinkLabel(content: string): string {
    const len = content.trim().length;
    if (len < 200) return 'Thought briefly';
    if (len < 800) return 'Thought for a moment';
    return 'Thought for a while';
}

function ThinkBlock({ content }: { content: string }) {
    const [open, setOpen] = useState(false);
    const label = thinkLabel(content);
    return (
        <div className="my-2 rounded border border-[var(--border)] bg-[var(--bg-secondary)]">
            <button
                onClick={() => setOpen(v => !v)}
                className="flex w-full items-center gap-2 px-3 py-2 text-xs text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
            >
                <Brain size={12} />
                <span>{label}</span>
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

    // Use external streaming store for active streaming messages.
    // getContentDeduped collapses repeating segments produced when the backend
    // retry policy replays the model stream on transient failures.
    const streamingContent = useSyncExternalStore(
        streamingStore.subscribe,
        () => (sessionId ? streamingStore.getContentDeduped(sessionId) : ''),
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
            <div className="flex justify-end mb-4 animate-slide-up group w-full min-w-0">
                <div className="max-w-[85%] min-w-0 px-4 py-2.5 rounded-2xl rounded-br-sm bg-[var(--color-primary)] text-white overflow-hidden">
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
                            {message.imageData && message.imageMediaType && (
                                <img
                                    src={`data:${message.imageMediaType};base64,${message.imageData}`}
                                    alt="attached image"
                                    className="max-h-48 rounded border border-white/20 mb-2"
                                />
                            )}
                            <p className="whitespace-pre-wrap break-words text-sm">{stripSystemReminders(message.content)}</p>
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

    const hasToolCalls = (message.toolCalls?.length ?? 0) > 0;
    const { think: thinkBlocks, rest: rawMainContent } = extractThinkBlocks(debouncedContent);
    // Apply markdown normalisers (e.g. break inline "3. foo 4. bar" into proper list items)
    // only on the post-stream content; running while streaming would re-flow incomplete
    // tokens and cause cursor jitter.
    const mainContent = isStreaming ? rawMainContent : normalizeAssistantMarkdown(rawMainContent);
    const hasContent = mainContent.length > 0;
    const hasThinking = !!message.thinking || thinkBlocks.length > 0;

    // Skip rendering completely empty assistant bubbles. These ghosts appear when an
    // iteration's TEXT_CHUNK creates a placeholder message that never accumulated text
    // (e.g. the model returned a tool-only turn, or AGENT_THINKING cleared activeMsgId
    // before any chunk pinned content). Rendering them produces an empty pill in the
    // chat between two real messages — see screenshot in M133.
    if (!isStreaming && !hasContent && !hasToolCalls && !hasThinking) {
        return null;
    }

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
                    {message.thinking && <ThinkBlock content={message.thinking} />}
                    {thinkBlocks.map((t, i) => <ThinkBlock key={i} content={t} />)}

                    {hasContent && (() => {
                        const headings = extractHeadings(mainContent);
                        const useReport = shouldUseReportLayout(mainContent, headings);
                        const slugCounts = new Map<string, number>();
                        const slugify = (text: string): string => {
                            const base = text.toLowerCase()
                                .replace(/[^\w\u4e00-\u9fa5\s-]/g, '')
                                .replace(/\s+/g, '-')
                                .slice(0, 60) || `h-${slugCounts.size}`;
                            const n = slugCounts.get(base) ?? 0;
                            slugCounts.set(base, n + 1);
                            return n === 0 ? base : `${base}-${n}`;
                        };
                        const headingClass = (level: 1 | 2 | 3 | 4) => {
                            switch (level) {
                                case 1: return 'mt-4 mb-2 text-base font-semibold text-[var(--text-primary)] border-b border-[var(--border)] pb-1';
                                case 2: return 'mt-4 mb-2 text-[15px] font-semibold text-[var(--text-primary)] flex items-center gap-1.5';
                                case 3: return 'mt-3 mb-1.5 text-sm font-semibold text-[var(--text-primary)]';
                                case 4: return 'mt-2 mb-1 text-sm font-medium text-[var(--text-secondary)]';
                            }
                        };
                        const headingNode = (level: 1 | 2 | 3 | 4, children: React.ReactNode) => {
                            const text = String(children).replace(/[*_`]/g, '').trim();
                            const id = slugify(text);
                            const Tag = (`h${level}`) as keyof JSX.IntrinsicElements;
                            return (
                                <Tag data-heading-id={id} className={`${headingClass(level)} scroll-mt-20`}>
                                    {children}
                                </Tag>
                            );
                        };
                        const components: React.ComponentProps<typeof LazyMarkdown>['components'] = {
                            h1: ({ children }) => headingNode(1, children),
                            h2: ({ children }) => headingNode(2, children),
                            h3: ({ children }) => headingNode(3, children),
                            h4: ({ children }) => headingNode(4, children),
                            p({ children }) {
                                return <div className="mb-2 last:mb-0 leading-relaxed text-[13.5px]">{children}</div>;
                            },
                            ul({ children }) {
                                return <ul className="my-2 ml-5 list-disc marker:text-[var(--accent)] space-y-1 text-[13.5px] leading-relaxed">{children}</ul>;
                            },
                            ol({ children }) {
                                return <ol className="my-2 ml-5 list-decimal marker:text-[var(--text-muted)] space-y-1 text-[13.5px] leading-relaxed">{children}</ol>;
                            },
                            li({ children }) {
                                return <li className="pl-1">{children}</li>;
                            },
                            blockquote({ children }) {
                                return (
                                    <blockquote className="my-2 pl-3 border-l-2 border-[var(--accent)]/50 text-[var(--text-secondary)] italic">
                                        {children}
                                    </blockquote>
                                );
                            },
                            hr: () => <hr className="my-3 border-[var(--border)]" />,
                            a({ href, children }) {
                                return (
                                    <a
                                        href={href}
                                        target="_blank"
                                        rel="noreferrer"
                                        className="text-[var(--accent)] hover:underline"
                                    >
                                        {children}
                                    </a>
                                );
                            },
                            code(props) {
                                const { className, children, ...rest } = props as {
                                    className?: string;
                                    children: React.ReactNode;
                                } & Record<string, unknown>;
                                const propsRecord = props as Record<string, unknown>;
                                const match = /language-(\w+)/.exec(className || '');
                                const lang = match ? match[1] : '';
                                const content = String(children).replace(/\n$/, '');

                                // A. Inline-code downgrade: a fenced block that's a single short line of
                                // identifier-like text (no spaces/tabs) is almost always a path or token
                                // mistakenly fenced. Render as inline so the body keeps flow.
                                const looksLikeMicroBlock =
                                    !propsRecord.inline &&
                                    content &&
                                    !content.includes('\n') &&
                                    content.length <= 60 &&
                                    !content.includes(' ') &&
                                    !content.includes('\t') &&
                                    (lang === '' || lang === 'text' || lang === 'plaintext');

                                if (looksLikeMicroBlock) {
                                    const filePathRegex = /^\.?\/?([\w.-]+\/)*[\w.-]+\.[a-zA-Z]{1,10}$/;
                                    if (filePathRegex.test(content)) {
                                        return (
                                            <code
                                                className="px-1 py-0.5 rounded bg-[var(--bg-primary)] border border-[var(--border)]/50 text-[12px] font-mono"
                                                style={{ color: 'var(--accent)', cursor: 'pointer' }}
                                                onClick={() => onOpenFile?.(content)}
                                                title={`Open ${content}`}
                                            >
                                                {content}
                                            </code>
                                        );
                                    }
                                    return (
                                        <code className="px-1 py-0.5 rounded bg-[var(--bg-primary)] border border-[var(--border)]/50 text-[12px] font-mono text-[var(--text-primary)]">
                                            {content}
                                        </code>
                                    );
                                }

                                if (!propsRecord.inline && content) {
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
                        };
                        const body = (
                            <div className="prose prose-sm dark:prose-invert max-w-none">
                                {renderWithTables(mainContent, components)}
                            </div>
                        );
                        return useReport ? <ReportLayout headings={headings}>{body}</ReportLayout> : body;
                    })()}

                    {isStreaming && hasContent && (
                        <span className="typing-cursor" />
                    )}

                    {/* Empty streaming bubble: show an animated activity pulse so the user
                     *  can tell the agent is working (vs stuck) before any text/tool arrives.
                     *  The global ThinkingIndicator at the chat footer carries the precise phase
                     *  (Thinking/Running tool/Writing); this is just the in-bubble heartbeat. */}
                    {isStreaming && !hasContent && !hasToolCalls && !message.thinking && thinkBlocks.length === 0 && (
                        <div className="flex items-center gap-1.5 py-1 text-[var(--text-muted)]">
                            <span className="inline-block w-1.5 h-1.5 rounded-full bg-[var(--text-muted)] animate-bounce [animation-delay:-0.3s]" />
                            <span className="inline-block w-1.5 h-1.5 rounded-full bg-[var(--text-muted)] animate-bounce [animation-delay:-0.15s]" />
                            <span className="inline-block w-1.5 h-1.5 rounded-full bg-[var(--text-muted)] animate-bounce" />
                            <span className="ml-1 text-[12px]">Working…</span>
                        </div>
                    )}

                    {hasToolCalls && (
                        message.toolCalls?.length === 1 ? (
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
                    {!isStreaming && message.role === 'assistant' && (message.content?.length ?? 0) > 0 && (
                        <div className="text-[10px] text-[var(--text-muted)] mt-1 text-right">
                            {message.content.length.toLocaleString()} chars
                        </div>
                    )}
                </div>

                {/* Action row sits below the bubble so icons never overlap message text. */}
                {hasContent && (
                    <div className="flex items-center gap-1 mt-1 ml-2 opacity-0 group-hover:opacity-100 transition-opacity">
                        {message.timestamp && (
                            <span
                                className="text-[10px] text-[var(--text-muted)] select-none"
                                title={formatAbsoluteTime(message.timestamp)}
                            >
                                {formatRelativeTime(message.timestamp)}
                            </span>
                        )}
                        {onRegenerate && !isStreaming && (
                            <button
                                onClick={() => onRegenerate(message.id)}
                                className="flex items-center rounded px-1 py-0.5 hover:bg-[var(--bg-tertiary)] text-[var(--text-muted)] hover:text-[var(--text-primary)]"
                                title="Regenerate response"
                            >
                                <RefreshCw size={12} />
                            </button>
                        )}
                        {onToggleBookmark && (
                            <button
                                onClick={() => onToggleBookmark(message.id)}
                                className={`flex items-center rounded px-1 py-0.5 hover:bg-[var(--bg-tertiary)] transition-colors ${
                                    isBookmarked
                                        ? 'text-amber-400 hover:text-amber-300'
                                        : 'text-[var(--text-muted)] hover:text-amber-400'
                                }`}
                                title={isBookmarked ? 'Remove bookmark' : 'Bookmark message'}
                            >
                                <Star size={12} fill={isBookmarked ? 'currentColor' : 'none'} />
                            </button>
                        )}
                        <button
                            onClick={handleCopyMessage}
                            className="flex items-center rounded px-1 py-0.5 hover:bg-[var(--bg-tertiary)] text-[var(--text-muted)] hover:text-[var(--text-primary)]"
                            title="Copy message"
                        >
                            {copiedMsg ? <Check size={12} /> : <Copy size={12} />}
                        </button>
                        {message.id && <MessageReaction messageId={message.id} />}
                    </div>
                )}
            </div>
        </div>
    );
}
