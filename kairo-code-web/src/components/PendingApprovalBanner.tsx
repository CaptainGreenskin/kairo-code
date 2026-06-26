import { AlertTriangle } from 'lucide-react';
import { useBreakpoint } from '@hooks/useBreakpoint';

interface PendingApprovalBannerProps {
    count: number;
    onScrollToPending: () => void;
}

export function PendingApprovalBanner({ count, onScrollToPending }: PendingApprovalBannerProps) {
    const bp = useBreakpoint();
    const isMobile = bp === 'xs' || bp === 'sm';

    if (count === 0) return null;

    return (
        <div className="flex items-center gap-2 px-3 py-2 bg-amber-500/10 border-t border-amber-500/30 text-amber-400 text-xs">
            <AlertTriangle size={13} className="flex-shrink-0 animate-pulse" />
            <span className="flex-1">
                {count === 1
                    ? '1 tool awaiting approval'
                    : `${count} tools awaiting approval`}
                {isMobile ? (
                    <span className="opacity-70"> — Tap below to approve</span>
                ) : (
                    <>
                        {' — '}
                        <kbd className="px-1 py-0.5 rounded border border-amber-500/40 bg-amber-500/10 font-mono text-[10px]">y</kbd>
                        {' approve · '}
                        <kbd className="px-1 py-0.5 rounded border border-amber-500/40 bg-amber-500/10 font-mono text-[10px]">n</kbd>
                        {' reject'}
                    </>
                )}
            </span>
            <button
                onClick={onScrollToPending}
                className="text-[10px] underline hover:no-underline opacity-70 hover:opacity-100 transition-opacity flex-shrink-0"
            >
                Scroll to approval ↓
            </button>
        </div>
    );
}
