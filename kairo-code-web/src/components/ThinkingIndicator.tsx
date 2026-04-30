import { useEffect, useState } from 'react';

export type Phase = 'thinking' | 'tool' | 'writing';

interface ThinkingIndicatorProps {
    isVisible: boolean;
    toolName?: string;
    phase?: Phase;
    toolElapsed?: number; // ms
}

const formatElapsed = (ms: number): string => {
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
};

export function ThinkingIndicator({ isVisible, toolName, phase = 'thinking', toolElapsed }: ThinkingIndicatorProps) {
    const [dots, setDots] = useState('');

    useEffect(() => {
        if (!isVisible) return;
        const interval = setInterval(() => {
            setDots(d => d.length >= 3 ? '' : d + '.');
        }, 400);
        return () => clearInterval(interval);
    }, [isVisible]);

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

    return (
        <div className="flex justify-start">
            <div className="flex items-center gap-2 px-4 py-2 text-sm text-[var(--text-secondary)]">
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
            </div>
        </div>
    );
}
