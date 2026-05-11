import { useEffect, useRef, useState } from 'react';
import { Activity, Zap } from 'lucide-react';

interface ContextHealthProps {
    /** Current tokens used in the context window (input + output of latest turn). */
    tokenUsage: number;
    /** Configured context window size in tokens. */
    maxTokens: number;
    /** True briefly after a CONTEXT_COMPACTED event arrives. */
    isCompacting: boolean;
}

/**
 * Compact Header indicator showing context window fill (token usage / context window) plus a
 * short-lived "compacted" flash whenever the backend ContextCompactionHook fires. Hidden when
 * {@link maxTokens} is unknown to avoid showing a misleading 0% bar.
 */
export function ContextHealthIndicator({ tokenUsage, maxTokens, isCompacting }: ContextHealthProps) {
    const [justCompacted, setJustCompacted] = useState(false);
    const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    useEffect(() => {
        if (!isCompacting) return;
        // Pulse for 3s. If a new compaction arrives mid-flash, restart the timer rather
        // than tearing it down when the parent flips the prop back to false.
        setJustCompacted(true);
        if (timerRef.current) clearTimeout(timerRef.current);
        timerRef.current = setTimeout(() => {
            setJustCompacted(false);
            timerRef.current = null;
        }, 3000);
    }, [isCompacting]);

    useEffect(() => {
        return () => {
            if (timerRef.current) clearTimeout(timerRef.current);
        };
    }, []);

    if (!maxTokens || maxTokens <= 0) return null;

    const ratio = Math.min(1, Math.max(0, tokenUsage / maxTokens));
    const pct = Math.round(ratio * 100);

    const color = ratio < 0.6
        ? 'text-emerald-400'
        : ratio < 0.8
        ? 'text-yellow-400'
        : 'text-red-400';

    const barColor = ratio < 0.6
        ? 'bg-emerald-400'
        : ratio < 0.8
        ? 'bg-yellow-400'
        : 'bg-red-400';

    return (
        <div
            className="flex items-center gap-1 px-1 py-0.5 text-[11px]"
            title={`Context: ${tokenUsage.toLocaleString()} / ${maxTokens.toLocaleString()} tokens`}
            data-testid="context-health-indicator"
            aria-label={`Context usage ${pct}%${justCompacted ? ', just compacted' : ''}`}
        >
            {justCompacted ? (
                <Zap size={11} className="text-[var(--accent)] animate-pulse" aria-hidden="true" />
            ) : (
                <Activity size={11} className={color} aria-hidden="true" />
            )}
            <div className="w-12 h-1.5 bg-[var(--border)] rounded-full overflow-hidden">
                <div
                    className={`h-full rounded-full transition-all duration-1000 ${barColor}`}
                    style={{ width: `${pct}%` }}
                />
            </div>
            <span className={`font-mono ${color}`}>{pct}%</span>
            {justCompacted && (
                <span className="text-[var(--accent)] animate-fade-out" data-testid="context-compacted-flash">
                    compacted
                </span>
            )}
        </div>
    );
}
