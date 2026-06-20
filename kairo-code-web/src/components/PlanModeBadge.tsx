import { ClipboardList, X } from 'lucide-react';
import { usePlanModeStore } from '@store/planModeStore';

/**
 * Plan-mode badge: visible when plan mode is armed (pending), active (backend in plan
 * mode), or awaiting approval. Click toggles the pending flag — Shift+Tab in ChatInput
 * does the same. Mirrors Claude Code's plan-mode indicator.
 *
 * <p>When active or awaiting approval, an X button appears to force-clear plan mode state.
 */
export function PlanModeBadge() {
    const pending = usePlanModeStore((s) => s.pending);
    const active = usePlanModeStore((s) => s.active);
    const awaiting = usePlanModeStore((s) => s.awaitingApproval);
    const togglePending = usePlanModeStore((s) => s.togglePending);
    const reset = usePlanModeStore((s) => s.reset);

    const visible = pending || active || awaiting;
    if (!visible) return null;

    const label = awaiting ? 'plan ready' : active ? 'plan mode' : 'plan armed';
    const cls = awaiting
        ? 'border-purple-400 bg-purple-500/20 text-purple-300 animate-pulse'
        : active
            ? 'border-purple-500/50 bg-purple-500/15 text-purple-300'
            : 'border-purple-500/30 bg-purple-500/5 text-purple-300/80';

    return (
        <span className={`inline-flex items-center gap-1 rounded border text-[11px] font-medium transition-colors ${cls}`}>
            <button
                onClick={togglePending}
                className="flex items-center gap-1 px-2 py-1"
                title="Plan mode (Shift+Tab to toggle)"
            >
                <ClipboardList size={11} />
                {label}
            </button>
            <button
                onClick={(e) => { e.stopPropagation(); reset(); }}
                className="flex items-center px-1 py-1 hover:text-purple-100 text-purple-400/60 transition-colors"
                title="Exit plan mode"
            >
                <X size={11} />
            </button>
        </span>
    );
}
