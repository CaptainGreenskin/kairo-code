import { useEffect, useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import { useSessionStore } from '@store/sessionStore';

const YELLOW_THRESHOLD_MS = 30_000;
const RED_THRESHOLD_MS = 120_000;

interface Props {
    /** Force-cancel callback. Wired to {@code handleStop} in App.tsx. */
    onForceCancel: () => void;
}

/**
 * Compact warning strip shown inside the chat input when the agent loop is
 * marked running but the WS pipe has been silent for a while. Designed to
 * answer "is it hung?" without forcing the user to open the dev panel.
 *
 *   yellow at >30s   — agent has paused but might still be reasoning
 *   red    at >120s  — almost certainly stalled, surface a force-cancel button
 *
 * Returns null when not running or under threshold so it never adds height
 * during normal operation.
 */
export function StallIndicator({ onForceCancel }: Props) {
    const running = useSessionStore((s) => s.running);
    const lastEventAt = useSessionStore((s) => s.lastEventAt);
    const [now, setNow] = useState(() => Date.now());

    // Tick every second only while running so the elapsed counter animates,
    // but no needless re-renders the rest of the time.
    useEffect(() => {
        if (!running) return;
        const t = setInterval(() => setNow(Date.now()), 1000);
        return () => clearInterval(t);
    }, [running]);

    if (!running || lastEventAt === 0) return null;
    const elapsed = now - lastEventAt;
    if (elapsed < YELLOW_THRESHOLD_MS) return null;

    const seconds = Math.floor(elapsed / 1000);
    const isRed = elapsed >= RED_THRESHOLD_MS;

    const colorClass = isRed
        ? 'bg-[var(--color-danger,#ef4444)]/10 text-[var(--color-danger,#ef4444)] border-[var(--color-danger,#ef4444)]/30'
        : 'bg-[var(--color-warning,#f59e0b)]/10 text-[var(--color-warning,#f59e0b)] border-[var(--color-warning,#f59e0b)]/30';

    return (
        <div
            className={`mx-2 mt-1.5 flex items-center justify-between gap-2 px-2 py-1 rounded-md border text-[11px] ${colorClass}`}
        >
            <div className="flex items-center gap-1.5 min-w-0">
                <AlertTriangle size={12} className="shrink-0" />
                <span className="truncate">
                    {isRed
                        ? `agent silent ${seconds}s — likely stalled`
                        : `agent silent ${seconds}s · still waiting…`}
                </span>
            </div>
            {isRed && (
                <button
                    onClick={onForceCancel}
                    className="shrink-0 px-2 py-0.5 rounded bg-[var(--color-danger,#ef4444)] text-white hover:opacity-90 transition-opacity"
                    title="Force cancel the agent"
                >
                    Force Cancel
                </button>
            )}
        </div>
    );
}
