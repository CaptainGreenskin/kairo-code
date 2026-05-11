import { useEffect, useRef, useState, memo } from 'react';

export type Phase = 'thinking' | 'tool' | 'writing';

interface ThinkingIndicatorProps {
    isVisible: boolean;
    toolName?: string;
    phase?: Phase;
    toolElapsed?: number; // ms
    /** Live reasoning_content delta from thinking models. When present, renders below the dots. */
    thinkingText?: string;
};

const formatElapsed = (ms: number): string => {
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
};

function ThinkingIndicatorInner({ isVisible, toolName, phase = 'thinking', toolElapsed, thinkingText }: ThinkingIndicatorProps) {
    const [dots, setDots] = useState('');
    const [expanded, setExpanded] = useState(true);
    const scrollRef = useRef<HTMLDivElement | null>(null);

    useEffect(() => {
        if (!isVisible) return;
        const interval = setInterval(() => {
            setDots(d => d.length >= 3 ? '' : d + '.');
        }, 400);
        return () => clearInterval(interval);
    }, [isVisible]);

    // Auto-scroll thinking text to bottom as new chunks stream in
    useEffect(() => {
        if (scrollRef.current && expanded) {
            scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
        }
    }, [thinkingText, expanded]);

    if (!isVisible) return null;

    // Tool phase with spinner and elapsed time
    if (phase === 'tool') {
        return (
            <div className="flex justify-start">
                <div className="flex items-center gap-2 px-4 py-2 text-sm text-[var(--text-secondary)]">
                    <div className="w-3 h-3 rounded-full border-2 border-[var(--accent)] border-t-transparent animate-spin" />
                    <span>
                        Running <code className="text-[var(--text-primary)]">{toolName || 'tool'}</code>
                        {toolElapsed !== undefined && toolElapsed > 0 && (
                            <span className="ml-1 text-[var(--text-muted)]">
                                {formatElapsed(toolElapsed)}
                            </span>
                        )}
                    </span>
                </div>
            </div>
        );
    }

    const label =
        phase === 'writing' ? 'writing' :
        'thinking';

    const hasThinkingText = phase === 'thinking' && !!thinkingText && thinkingText.length > 0;

    return (
        <div className="flex justify-start">
            <div className="flex flex-col gap-1 px-4 py-2 text-sm text-[var(--text-secondary)] max-w-full">
                <button
                    type="button"
                    onClick={() => hasThinkingText && setExpanded(v => !v)}
                    className={`flex items-center gap-2 ${hasThinkingText ? 'cursor-pointer hover:text-[var(--text-primary)]' : 'cursor-default'} text-left`}
                >
                    <span className="flex gap-1">
                        {[0, 1, 2].map(i => (
                            <span
                                key={i}
                                className="w-1.5 h-1.5 rounded-full bg-[var(--text-secondary)] animate-bounce"
                                style={{ animationDelay: `${i * 0.15}s`, animationDuration: '0.8s' }}
                            />
                        ))}
                    </span>
                    <span className="italic">{label}{dots}</span>
                    {hasThinkingText && (
                        <span className="text-xs text-[var(--text-muted)] ml-1">
                            {expanded ? '▾' : '▸'}
                        </span>
                    )}
                </button>
                {hasThinkingText && expanded && (
                    <div
                        ref={scrollRef}
                        className="ml-5 max-h-48 overflow-y-auto whitespace-pre-wrap text-xs text-[var(--text-muted)] italic border-l-2 border-[var(--border)] pl-3 py-1"
                    >
                        {thinkingText}
                    </div>
                )}
            </div>
        </div>
    );
}

export const ThinkingIndicator = memo(ThinkingIndicatorInner);
