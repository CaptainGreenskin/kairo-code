import { useEffect, useRef, useState } from 'react';
import { Activity, Zap } from 'lucide-react';

interface ContextHealthProps {
    tokenUsage: number;
    maxTokens: number;
    isCompacting: boolean;
    onCompact?: () => void;
    onNewChat?: () => void;
}

export function ContextHealthIndicator({ tokenUsage, maxTokens, isCompacting, onCompact, onNewChat }: ContextHealthProps) {
    const [justCompacted, setJustCompacted] = useState(false);
    const [showPopup, setShowPopup] = useState(false);
    const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const popupRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        if (!isCompacting) return;
        setJustCompacted(true);
        if (timerRef.current) clearTimeout(timerRef.current);
        timerRef.current = setTimeout(() => {
            setJustCompacted(false);
            timerRef.current = null;
        }, 3000);
    }, [isCompacting]);

    useEffect(() => {
        return () => { if (timerRef.current) clearTimeout(timerRef.current); };
    }, []);

    // Close popup on outside click
    useEffect(() => {
        if (!showPopup) return;
        const handler = (e: MouseEvent) => {
            if (popupRef.current && !popupRef.current.contains(e.target as Node)) {
                setShowPopup(false);
            }
        };
        document.addEventListener('mousedown', handler);
        return () => document.removeEventListener('mousedown', handler);
    }, [showPopup]);

    if (!maxTokens || maxTokens <= 0) return null;

    const ratio = Math.min(1, Math.max(0, tokenUsage / maxTokens));
    const pct = Math.round(ratio * 100);
    const usedK = (tokenUsage / 1000).toFixed(1);
    const maxK = Math.round(maxTokens / 1000);

    const color = ratio < 0.6 ? 'text-emerald-400' : ratio < 0.8 ? 'text-yellow-400' : 'text-red-400';
    const barColor = ratio < 0.6 ? 'bg-emerald-400' : ratio < 0.8 ? 'bg-yellow-400' : 'bg-red-400';

    return (
        <div className="relative" ref={popupRef}>
            <button
                onClick={() => setShowPopup(v => !v)}
                className="flex items-center gap-1 px-1.5 py-0.5 text-[11px] rounded hover:bg-[var(--bg-hover)] transition-colors"
                title={`Context: ${tokenUsage.toLocaleString()} / ${maxTokens.toLocaleString()} tokens`}
                aria-label={`Context usage ${pct}%`}
            >
                {justCompacted ? (
                    <Zap size={11} className="text-[var(--accent)] animate-pulse" />
                ) : (
                    <Activity size={11} className={color} />
                )}
                <div className="w-12 h-1.5 bg-[var(--border)] rounded-full overflow-hidden">
                    <div className={`h-full rounded-full transition-all duration-1000 ${barColor}`} style={{ width: `${pct}%` }} />
                </div>
                <span className={`font-mono ${color}`}>{pct}%</span>
            </button>

            {showPopup && (
                <div className="absolute bottom-full right-0 mb-2 p-3 rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] shadow-xl z-50 min-w-[220px]">
                    <div className="text-xs font-medium text-[var(--text-primary)] mb-2">
                        <span className={`font-mono text-sm ${color}`}>{pct}%</span>
                        {' '}
                        <span className="text-[var(--text-muted)]">{usedK}k / {maxK}k</span>
                        {' '}
                        <span className="text-[var(--text-muted)]">Context used</span>
                    </div>
                    <div className="flex gap-2">
                        {onCompact && (
                            <button
                                onClick={() => { onCompact(); setShowPopup(false); }}
                                className="flex-1 px-3 py-1.5 text-xs font-medium rounded bg-[var(--bg-primary)] border border-[var(--border)] text-[var(--text-primary)] hover:bg-[var(--bg-hover)] transition-colors"
                            >
                                Compact Chat
                            </button>
                        )}
                        {onNewChat && (
                            <button
                                onClick={() => { onNewChat(); setShowPopup(false); }}
                                className="flex-1 px-3 py-1.5 text-xs font-medium rounded bg-[var(--bg-primary)] border border-[var(--border)] text-[var(--text-primary)] hover:bg-[var(--bg-hover)] transition-colors"
                            >
                                New Chat
                            </button>
                        )}
                    </div>
                </div>
            )}
        </div>
    );
}
