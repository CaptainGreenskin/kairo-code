import { useSyncExternalStore, useState } from 'react';
import Markdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { vscDarkPlus } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { Loader2, Copy, Check } from 'lucide-react';
import type { Message } from '@/types/agent';
import { ToolCallCard } from './ToolCallCard';
import { ToolCallGroup } from './ToolCallGroup';
import { streamingStore } from '@store/streamingStore';

interface ChatMessageProps {
    message: Message;
    onApproveTool?: (toolCallId: string, approved: boolean) => void;
    isStreaming?: boolean;
    sessionId?: string;
}

function CodeBlock({ language, content }: { language: string; content: string }) {
    const [copied, setCopied] = useState(false);

    const handleCopy = () => {
        navigator.clipboard.writeText(content).then(() => {
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
        });
    };

    return (
        <div className="relative group">
            <div className="flex items-center justify-between px-3 py-1 bg-[var(--color-code-bg)] border border-[var(--color-code-border)] border-b-0 rounded-t-md text-xs text-[var(--text-muted)]">
                <span>{language}</span>
                <button
                    onClick={handleCopy}
                    className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity hover:text-[var(--text-primary)]"
                    aria-label="Copy code"
                >
                    {copied ? <Check size={12} /> : <Copy size={12} />}
                    <span>{copied ? 'Copied!' : 'Copy'}</span>
                </button>
            </div>
            <SyntaxHighlighter
                style={vscDarkPlus as never}
                language={language}
                PreTag="div"
                customStyle={{ margin: 0, borderRadius: '0 0 6px 6px', borderTop: 'none' }}
            >
                {content}
            </SyntaxHighlighter>
        </div>
    );
}

export function ChatMessage({ message, onApproveTool, isStreaming, sessionId }: ChatMessageProps) {
    const [copiedMsg, setCopiedMsg] = useState(false);

    // Use external streaming store for active streaming messages
    const streamingContent = useSyncExternalStore(
        streamingStore.subscribe,
        () => (sessionId ? streamingStore.getContent(sessionId) : ''),
    );

    const displayContent = isStreaming && sessionId ? streamingContent : message.content;

    if (message.role === 'user') {
        return (
            <div className="flex justify-end mb-4 animate-slide-up">
                <div className="max-w-[80%] px-4 py-2.5 rounded-2xl rounded-br-sm bg-[var(--color-primary)] text-white">
                    <p className="whitespace-pre-wrap text-sm">{message.content}</p>
                    <span className="text-[10px] opacity-60 mt-1 block">
                        {new Date(message.timestamp).toLocaleTimeString()}
                    </span>
                </div>
            </div>
        );
    }

    const hasToolCalls = message.toolCalls.length > 0;
    const hasContent = message.content.length > 0;

    const handleCopyMessage = () => {
        navigator.clipboard.writeText(message.content).then(() => {
            setCopiedMsg(true);
            setTimeout(() => setCopiedMsg(false), 2000);
        });
    };

    return (
        <div className="flex justify-start mb-4 animate-slide-up">
            <div className="max-w-[85%]">
                <div className="relative group px-4 py-2.5 rounded-2xl rounded-bl-sm bg-[var(--bg-secondary)] border border-[var(--border)]">
                    {/* Message-level action buttons */}
                    <div className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity flex gap-1">
                        <button
                            onClick={handleCopyMessage}
                            className="flex items-center gap-1 rounded px-1.5 py-1 hover:bg-[var(--bg-tertiary)] text-[var(--text-muted)] hover:text-[var(--text-primary)]"
                            title="Copy message"
                        >
                            {copiedMsg ? <Check size={14} /> : <Copy size={14} />}
                        </button>
                    </div>

                    {hasContent && (
                        <div className="prose prose-sm dark:prose-invert max-w-none">
                            <Markdown
                                components={{
                                    code(props) {
                                        const { className, children, ...rest } = props as {
                                            className?: string;
                                            children: React.ReactNode;
                                            inline?: boolean;
                                        };
                                        const match = /language-(\w+)/.exec(className || '');
                                        const content = String(children).replace(/\n$/, '');
                                        if (!(props as Record<string, unknown>).inline && match && content) {
                                            return (
                                                <CodeBlock language={match[1]} content={content} />
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
                                {displayContent}
                            </Markdown>
                        </div>
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

                <span className="text-[10px] text-[var(--text-muted)] mt-1 ml-2 block">
                    {new Date(message.timestamp).toLocaleTimeString()}
                </span>
            </div>
        </div>
    );
}

export function ThinkingIndicator() {
    return (
        <div className="flex justify-start mb-4">
            <div className="px-4 py-2.5 rounded-2xl rounded-bl-sm bg-[var(--bg-secondary)] border border-[var(--border)]">
                <div className="flex items-center gap-2 text-[var(--text-muted)] text-sm">
                    <Loader2 size={16} className="animate-spin" />
                    <span>Thinking...</span>
                </div>
            </div>
        </div>
    );
}
