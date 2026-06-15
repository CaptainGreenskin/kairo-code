import { useState } from 'react';
import { ChevronDown, ChevronRight, Clock } from 'lucide-react';
import type { Message } from '@/types/agent';

interface QueuedMessagesCardProps {
    messages: Message[];
}

export function QueuedMessagesCard({ messages }: QueuedMessagesCardProps) {
    const [expanded, setExpanded] = useState(true);

    if (messages.length === 0) return null;

    return (
        <div className="mb-4 mx-2 rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] overflow-hidden">
            <button
                onClick={() => setExpanded((v) => !v)}
                className="w-full flex items-center gap-2 px-3 py-2 text-xs text-[var(--text-muted)] hover:bg-[var(--bg-hover)] transition-colors"
            >
                {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                <Clock size={13} className="text-yellow-400" />
                <span className="font-medium text-[var(--text-primary)]">
                    Message queued {messages.length}
                </span>
            </button>
            {expanded && (
                <div className="px-4 pb-2 space-y-1">
                    {messages.map((m) => (
                        <div key={m.id} className="text-xs text-[var(--text-muted)] italic truncate">
                            {m.content.length > 80 ? m.content.slice(0, 80) + '...' : m.content}
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
