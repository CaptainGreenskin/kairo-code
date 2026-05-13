import { useState } from 'react';
import { Undo2 } from 'lucide-react';
import type { PlanPhase } from '@/types/agent';

interface RevertButtonProps {
    sessionId: string;
    isGit: boolean;
    phase: PlanPhase;
    onRevert: () => void;
}

/**
 * Revert button shown after execution completes (FAILED_EXECUTION or COMPLETED).
 * Only available in git workspaces. Sends a revert action via WS on confirmation.
 */
export function RevertButton({ sessionId: _sessionId, isGit, phase, onRevert }: RevertButtonProps) {
    const [showConfirm, setShowConfirm] = useState(false);

    // Only render when phase is terminal (not during EXECUTING)
    if (phase !== 'FAILED_EXECUTION' && phase !== 'COMPLETED') {
        return null;
    }

    if (!isGit) {
        return (
            <button
                disabled
                className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-md
                    bg-[var(--bg-hover)] text-[var(--text-muted)] border border-[var(--border)]
                    opacity-50 cursor-not-allowed"
                title="Revert requires a git workspace"
            >
                <Undo2 size={13} />
                Revert
            </button>
        );
    }

    if (showConfirm) {
        return (
            <div className="flex items-center gap-2 px-3 py-2 text-xs rounded-md
                bg-rose-500/10 border border-rose-500/20">
                <span className="text-rose-300">Revert all changes since last build?</span>
                <button
                    onClick={() => setShowConfirm(false)}
                    className="px-2 py-0.5 rounded text-[var(--text-secondary)] border border-[var(--border)]
                        hover:bg-[var(--bg-hover)] transition-colors"
                >
                    Cancel
                </button>
                <button
                    onClick={() => {
                        setShowConfirm(false);
                        onRevert();
                    }}
                    className="px-2 py-0.5 rounded text-rose-300 border border-rose-500/30
                        bg-rose-500/10 hover:bg-rose-500/20 transition-colors"
                >
                    Revert
                </button>
            </div>
        );
    }

    return (
        <button
            onClick={() => setShowConfirm(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-md
                bg-[var(--bg-hover)] text-[var(--text-secondary)] border border-[var(--border)]
                hover:text-rose-300 hover:border-rose-500/30 hover:bg-rose-500/10 transition-colors"
        >
            <Undo2 size={13} />
            Revert
        </button>
    );
}
