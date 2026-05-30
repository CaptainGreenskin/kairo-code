import { useCallback } from 'react';
import { Hammer } from 'lucide-react';

interface ConfirmBuildChipProps {
    sessionId: string;
    isVisible: boolean;
    onConfirm: () => void;
}

/**
 * Inline chip rendered below a plan message when PLAN_READY has been received.
 * Shows an explicit "Approve and Build" button. Confirmation is always an explicit
 * user click — there is no keyword-triggered auto-build countdown.
 */
export function ConfirmBuildChip({ sessionId: _sessionId, isVisible, onConfirm }: ConfirmBuildChipProps) {
    const handleConfirm = useCallback(() => {
        onConfirm();
    }, [onConfirm]);

    if (!isVisible) return null;

    return (
        <div className="mt-3 mb-2 flex flex-col gap-2">
            <div className="flex items-center gap-2">
                <button
                    onClick={handleConfirm}
                    className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-md
                        bg-emerald-500/15 text-emerald-400 border border-emerald-500/30
                        hover:bg-emerald-500/25 hover:border-emerald-500/50 transition-colors"
                >
                    <Hammer size={13} />
                    Approve and Build
                </button>
            </div>
        </div>
    );
}
