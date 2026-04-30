import Markdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { vscDarkPlus } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { Loader2 } from 'lucide-react';
import type { Message } from '@/types/agent';
import { ToolCallCard } from './ToolCallCard';

interface ChatMessageProps {
    message: Message;
    onApproveTool?: (toolCallId: string, approved: boolean) => void;
}

export function ChatMessage({ message, onApproveTool }: ChatMessageProps) {
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

    return (
        <div className="flex justify-start mb-4 animate-slide-up">
            <div className="max-w-[85%]">
                <div className="px-4 py-2.5 rounded-2xl rounded-bl-sm bg-[var(--bg-secondary)] border border-[var(--border)]">
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
                                        if (!(props as Record<string, unknown>).inline && match) {
                                            return (
                                                <SyntaxHighlighter
                                                    style={vscDarkPlus as never}
                                                    language={match[1]}
                                                    PreTag="div"
                                                    {...rest}
                                                >
                                                    {content}
                                                </SyntaxHighlighter>
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
                                {message.content}
                            </Markdown>
                        </div>
                    )}

                    {hasToolCalls && (
                        <div className="mt-2 space-y-2">
                            {message.toolCalls.map((tc) => (
                                <ToolCallCard
                                    key={tc.id}
                                    toolCall={tc}
                                    onApprove={onApproveTool}
                                />
                            ))}
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
