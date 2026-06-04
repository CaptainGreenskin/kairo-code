import { useState } from 'react';
import { Zap, ChevronDown, ChevronUp } from 'lucide-react';
import { LazyMarkdown } from './LazyMarkdown';

interface CompactionEventCardProps {
    beforeTokens: number;
    maxTokens: number;
    timestamp: number;
    summary?: string;
}

export function CompactionEventCard({ beforeTokens, maxTokens, timestamp, summary }: CompactionEventCardProps) {
    const [expanded, setExpanded] = useState(false);
    const pct = maxTokens > 0 ? Math.round((beforeTokens / maxTokens) * 100) : 0;
    const time = new Date(timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    const hasSummary = summary && summary.trim().length > 0;

    return (
        <div className="flex flex-col items-center my-3 animate-slide-up">
            <button
                className="flex items-center gap-2 px-4 py-1.5 rounded-full text-xs transition-colors"
                style={{
                    background: 'var(--bg-secondary)',
                    border: '1px solid var(--border)',
                    cursor: hasSummary ? 'pointer' : 'default',
                }}
                onClick={() => hasSummary && setExpanded(!expanded)}
            >
                <Zap size={12} className="text-[var(--accent)] shrink-0" />
                <span style={{ color: 'var(--text-secondary)' }}>
                    Context compacted
                </span>
                <span className="font-mono" style={{ color: 'var(--text-tertiary)' }}>
                    {beforeTokens.toLocaleString()} / {maxTokens.toLocaleString()} ({pct}%)
                </span>
                <span style={{ color: 'var(--text-tertiary)' }}>{time}</span>
                {hasSummary && (
                    expanded
                        ? <ChevronUp size={12} style={{ color: 'var(--text-tertiary)' }} />
                        : <ChevronDown size={12} style={{ color: 'var(--text-tertiary)' }} />
                )}
            </button>
            {expanded && hasSummary && (
                <div className="mt-2 w-full max-w-[85%] px-4 py-3 rounded-lg text-sm"
                     style={{
                         background: 'var(--bg-secondary)',
                         border: '1px solid var(--border)',
                         color: 'var(--text-secondary)',
                     }}>
                    <LazyMarkdown>{summary}</LazyMarkdown>
                </div>
            )}
        </div>
    );
}
