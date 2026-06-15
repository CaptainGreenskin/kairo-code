import { useState } from 'react';
import { Clock, X, ChevronDown, ChevronRight } from 'lucide-react';
import type { Message } from '@/types/agent';

interface QueueBannerProps {
    count: number;
    messages: Message[];
    onCancelAll: () => void;
}

export function QueueBanner({ count, messages, onCancelAll }: QueueBannerProps) {
    const [expanded, setExpanded] = useState(false);

    if (count <= 0) return null;

    return (
        <div className="border-t border-[var(--border)] bg-[var(--bg-secondary)]">
            <div className="flex items-center justify-between px-3 py-1.5 text-xs">
                <button
                    onClick={() => setExpanded((v) => !v)}
                    className="flex items-center gap-2 text-[var(--color-primary)] hover:opacity-80 transition-opacity"
                >
                    {expanded ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
                    <Clock size={13} className="animate-pulse" />
                    <span>{count} 条消息排队中，等待当前任务完成后自动执行</span>
                </button>
                <button
                    onClick={onCancelAll}
                    className="flex items-center gap-1 px-2 py-0.5 rounded text-[var(--text-muted)] hover:text-[var(--color-danger)] hover:bg-[var(--color-danger)]/10 transition-colors"
                >
                    <X size={12} />
                    <span>取消全部</span>
                </button>
            </div>
            {expanded && messages.length > 0 && (
                <div className="px-4 pb-2 space-y-0.5">
                    {messages.map((m, i) => (
                        <div key={m.id ?? i} className="text-xs text-[var(--text-muted)] italic truncate pl-6">
                            {i + 1}. {m.content.length > 60 ? m.content.slice(0, 60) + '...' : m.content}
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
