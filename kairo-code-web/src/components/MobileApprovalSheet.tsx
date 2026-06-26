import { useState, useCallback, useMemo } from 'react';
import { Check, X, ChevronUp, ChevronDown, ChevronLeft, ChevronRight, AlertTriangle, Terminal } from 'lucide-react';
import { useSessionStore } from '@store/sessionStore';
import type { ToolCall } from '@/types/agent';

interface MobileApprovalSheetProps {
    onApprove: (toolCallId: string, approved: boolean, reason?: string) => void;
}

/**
 * Mobile-optimized sticky bottom sheet for tool call approvals.
 * Shows when there are pending tool calls on mobile devices.
 * Large touch targets (44px+), swipe between multiple pending items.
 */
export function MobileApprovalSheet({ onApprove }: MobileApprovalSheetProps) {
    const messages = useSessionStore((s) => s.messages);
    const [currentIndex, setCurrentIndex] = useState(0);
    const [expanded, setExpanded] = useState(false);

    // Collect all pending tool calls
    const pendingTools = useMemo(() => {
        const pending: { toolCall: ToolCall; messageId: string }[] = [];
        for (const msg of messages) {
            for (const tc of msg.toolCalls ?? []) {
                if (tc.requiresApproval && tc.status === 'pending') {
                    pending.push({ toolCall: tc, messageId: msg.id });
                }
            }
        }
        return pending;
    }, [messages]);

    const count = pendingTools.length;

    // Reset index if out of bounds
    const safeIndex = Math.min(currentIndex, Math.max(0, count - 1));
    const current = pendingTools[safeIndex];

    const handlePrev = useCallback(() => {
        setCurrentIndex((i) => Math.max(0, i - 1));
    }, []);

    const handleNext = useCallback(() => {
        setCurrentIndex((i) => Math.min(count - 1, i + 1));
    }, [count]);

    if (count === 0 || !current) return null;

    const { toolCall } = current;
    const argsPreview = toolCall.input
        ? JSON.stringify(toolCall.input).slice(0, 100)
        : '';

    return (
        <div className="fixed bottom-0 left-0 right-0 z-50 safe-area-bottom">
            {/* Backdrop when expanded */}
            {expanded && (
                <div
                    className="fixed inset-0 bg-black/30 z-40"
                    onClick={() => setExpanded(false)}
                />
            )}

            <div
                className={`relative z-50 bg-[var(--bg-secondary)] border-t border-[var(--border)] shadow-[0_-4px_20px_rgba(0,0,0,0.3)] transition-all duration-200 ${
                    expanded ? 'max-h-[60vh]' : 'max-h-[180px]'
                }`}
            >
                {/* Drag handle */}
                <div
                    className="flex justify-center py-2 cursor-pointer"
                    onClick={() => setExpanded((e) => !e)}
                >
                    <div className="w-8 h-1 rounded-full bg-[var(--text-muted)]/40" />
                </div>

                {/* Header: warning + tool name + pagination */}
                <div className="px-4 pb-2 flex items-center gap-2">
                    <AlertTriangle size={16} className="text-amber-400 animate-pulse shrink-0" />
                    <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2">
                            <Terminal size={13} className="text-[var(--text-muted)] shrink-0" />
                            <code className="text-sm font-mono font-semibold text-[var(--text-primary)] truncate">
                                {toolCall.toolName}
                            </code>
                        </div>
                        {!expanded && argsPreview && (
                            <p className="text-[11px] text-[var(--text-muted)] truncate mt-0.5 pl-[21px]">
                                {argsPreview}
                            </p>
                        )}
                    </div>
                    {count > 1 && (
                        <div className="flex items-center gap-1 shrink-0">
                            <button
                                onClick={handlePrev}
                                disabled={safeIndex === 0}
                                className="p-1.5 rounded text-[var(--text-muted)] disabled:opacity-30"
                            >
                                <ChevronLeft size={16} />
                            </button>
                            <span className="text-[11px] text-[var(--text-muted)] font-mono min-w-[2rem] text-center">
                                {safeIndex + 1}/{count}
                            </span>
                            <button
                                onClick={handleNext}
                                disabled={safeIndex === count - 1}
                                className="p-1.5 rounded text-[var(--text-muted)] disabled:opacity-30"
                            >
                                <ChevronRight size={16} />
                            </button>
                        </div>
                    )}
                    <button
                        onClick={() => setExpanded((e) => !e)}
                        className="p-1.5 rounded text-[var(--text-muted)]"
                    >
                        {expanded ? <ChevronDown size={16} /> : <ChevronUp size={16} />}
                    </button>
                </div>

                {/* Expanded: full args view */}
                {expanded && (
                    <div className="px-4 pb-3 max-h-[30vh] overflow-y-auto">
                        <pre className="text-xs font-mono text-[var(--text-secondary)] bg-[var(--bg-primary)] rounded-lg p-3 overflow-x-auto whitespace-pre-wrap break-all">
                            {JSON.stringify(toolCall.input, null, 2)}
                        </pre>
                    </div>
                )}

                {/* Action buttons: large touch targets */}
                <div className="px-4 pb-4 flex gap-3">
                    <button
                        onClick={() => onApprove(toolCall.id, false)}
                        className="flex-1 flex items-center justify-center gap-2 py-3.5 rounded-xl text-sm font-semibold text-white bg-red-600 active:bg-red-700 transition-colors min-h-[48px]"
                    >
                        <X size={18} />
                        Reject
                    </button>
                    <button
                        onClick={() => onApprove(toolCall.id, true)}
                        className="flex-[2] flex items-center justify-center gap-2 py-3.5 rounded-xl text-sm font-semibold text-white bg-emerald-600 active:bg-emerald-700 transition-colors min-h-[48px]"
                    >
                        <Check size={18} />
                        Approve
                    </button>
                </div>
            </div>
        </div>
    );
}
