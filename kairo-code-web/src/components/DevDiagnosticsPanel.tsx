import { useEffect, useState } from 'react';
import { useSessionStore } from '@store/sessionStore';
import { getDiagnostics, type SessionDiagnostics } from '@api/diagnostics';

/**
 * Dev-only diagnostics overlay. Mounted from App.tsx but rendered as null in
 * production (gated by {@code import.meta.env.DEV}). Pinned to bottom-right.
 *
 * The point: when a session is "stuck" you want to compare the frontend's view
 * (WS event counts, lastEventAt, running flag) with the server's view (the
 * /api/sessions/:id/diagnostics endpoint). If the two disagree the WS pipe
 * is the bug; if they agree the agent is genuinely stalled.
 */
export function DevDiagnosticsPanel() {
    const [open, setOpen] = useState(false);
    const [diag, setDiag] = useState<SessionDiagnostics | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [loading, setLoading] = useState(false);
    const [now, setNow] = useState(() => Date.now());

    const sessionId = useSessionStore((s) => s.sessionId);
    const running = useSessionStore((s) => s.running);
    const isThinking = useSessionStore((s) => s.isThinking);
    const lastEventAt = useSessionStore((s) => s.lastEventAt);

    // Tick a clock so "Xs ago" labels animate without subscribing to every
    // store mutation. Keeps the panel cheap when collapsed.
    useEffect(() => {
        if (!open) return;
        const t = setInterval(() => setNow(Date.now()), 1000);
        return () => clearInterval(t);
    }, [open]);

    const refresh = async () => {
        if (!sessionId) {
            setDiag(null);
            setError('no active session');
            return;
        }
        setLoading(true);
        setError(null);
        try {
            const d = await getDiagnostics(sessionId);
            setDiag(d);
            if (!d) setError('session unknown on server (404)');
        } catch (e) {
            setError(e instanceof Error ? e.message : String(e));
            setDiag(null);
        } finally {
            setLoading(false);
        }
    };

    if (!import.meta.env.DEV) return null;

    if (!open) {
        return (
            <button
                onClick={() => setOpen(true)}
                className="fixed bottom-3 right-3 z-50 px-2 py-1 text-[10px] font-mono rounded-md bg-[var(--bg-elevated,#1f2937)] text-[var(--text-muted,#9ca3af)] border border-[var(--border,#374151)] hover:text-[var(--text-primary,#f3f4f6)] shadow-md"
                title="Open dev diagnostics"
            >
                diag
            </button>
        );
    }

    const sinceLast = lastEventAt > 0 ? Math.floor((now - lastEventAt) / 1000) : null;

    return (
        <div className="fixed bottom-3 right-3 z-50 w-80 max-h-[60vh] overflow-auto rounded-md bg-[var(--bg-elevated,#111827)] text-[var(--text-primary,#f3f4f6)] border border-[var(--border,#374151)] shadow-lg text-[11px] font-mono">
            <div className="flex items-center justify-between px-2 py-1.5 border-b border-[var(--border,#374151)] bg-[var(--bg-secondary,#1f2937)]">
                <span className="font-semibold">dev diagnostics</span>
                <button
                    onClick={() => setOpen(false)}
                    className="text-[var(--text-muted,#9ca3af)] hover:text-[var(--text-primary,#f3f4f6)]"
                    title="Close"
                >
                    ✕
                </button>
            </div>

            <div className="p-2 space-y-1">
                <Row label="sessionId" value={sessionId ?? '(none)'} />
                <Row label="running (FE)" value={String(running)} />
                <Row label="isThinking" value={String(isThinking)} />
                <Row
                    label="lastEventAt"
                    value={
                        lastEventAt === 0
                            ? '(none)'
                            : `${sinceLast}s ago`
                    }
                    warn={sinceLast !== null && running && sinceLast > 30}
                />
            </div>

            <div className="px-2 pb-2 flex gap-1">
                <button
                    onClick={refresh}
                    disabled={loading || !sessionId}
                    className="flex-1 px-2 py-1 rounded bg-[var(--accent,#3b82f6)] text-white disabled:opacity-50 disabled:cursor-not-allowed hover:bg-[var(--accent-hover,#2563eb)]"
                >
                    {loading ? '…' : 'fetch /diagnostics'}
                </button>
            </div>

            {error && (
                <div className="px-2 pb-2 text-[var(--color-danger,#ef4444)]">
                    {error}
                </div>
            )}

            {diag && (
                <div className="px-2 pb-2 space-y-1 border-t border-[var(--border,#374151)] pt-2">
                    <Row label="running (BE)" value={String(diag.running)} />
                    <Row label="wsClients" value={String(diag.wsClients)} />
                    <Row
                        label="msSinceLastEvent"
                        value={`${diag.msSinceLastEvent}ms`}
                        warn={diag.running && diag.msSinceLastEvent > 30000}
                    />
                    <div className="pt-1">
                        <div className="text-[var(--text-muted,#9ca3af)] mb-0.5">eventCounts</div>
                        {Object.entries(diag.eventCounts).length === 0 ? (
                            <div className="text-[var(--text-muted,#9ca3af)] pl-2">(none)</div>
                        ) : (
                            Object.entries(diag.eventCounts).map(([k, v]) => (
                                <Row key={k} label={k} value={String(v)} indent />
                            ))
                        )}
                    </div>
                </div>
            )}
        </div>
    );
}

function Row({
    label,
    value,
    warn,
    indent,
}: {
    label: string;
    value: string;
    warn?: boolean;
    indent?: boolean;
}) {
    return (
        <div className={`flex justify-between gap-2 ${indent ? 'pl-2' : ''}`}>
            <span className="text-[var(--text-muted,#9ca3af)]">{label}</span>
            <span className={warn ? 'text-[var(--color-warning,#f59e0b)]' : ''}>{value}</span>
        </div>
    );
}
