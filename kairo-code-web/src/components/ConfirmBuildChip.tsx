import { useState, useEffect, useRef, useCallback } from 'react';
import { Hammer, X } from 'lucide-react';
import { useBuildPhaseStore } from '@store/buildPhaseStore';

interface ConfirmBuildChipProps {
    sessionId: string;
    isVisible: boolean;
    onConfirm: () => void;
}

/** Keywords that trigger the auto-build countdown (case-insensitive, trimmed). */
const CONFIRM_KEYWORDS_EN = ['go', 'build', 'approved', 'ok', 'start'];
const CONFIRM_KEYWORDS_ZH = ['开始', '确认', '行', '做吧'];
const ALL_KEYWORDS = [...CONFIRM_KEYWORDS_EN, ...CONFIRM_KEYWORDS_ZH];

/**
 * Returns true if the given text is a confirm keyword.
 * Only triggers when text.length < 50.
 */
export function isConfirmKeyword(text: string): boolean {
    const trimmed = text.trim();
    if (trimmed.length === 0 || trimmed.length >= 50) return false;
    const lower = trimmed.toLowerCase();
    return ALL_KEYWORDS.includes(lower);
}

/** Check if a confirm keyword is Chinese (for i18n of countdown text). */
export function isChinese(text: string): boolean {
    const trimmed = text.trim().toLowerCase();
    return CONFIRM_KEYWORDS_ZH.includes(trimmed);
}

const COUNTDOWN_SECONDS = 5;

/**
 * Inline chip rendered below a plan message when PLAN_READY has been received.
 * Shows "Approve and Build" + optional "Open in Editor" buttons.
 * Also owns the keyword-triggered countdown timer.
 */
export function ConfirmBuildChip({ sessionId: _sessionId, isVisible, onConfirm }: ConfirmBuildChipProps) {
    const [countdown, setCountdown] = useState<number | null>(null);
    const [countdownChinese, setCountdownChinese] = useState(false);
    const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
    const phase = useBuildPhaseStore((s) => s.phase);

    const clearTimer = useCallback(() => {
        if (timerRef.current) {
            clearInterval(timerRef.current);
            timerRef.current = null;
        }
        setCountdown(null);
    }, []);

    // Clean up on unmount
    useEffect(() => {
        return () => {
            if (timerRef.current) clearInterval(timerRef.current);
        };
    }, []);

    // If phase moves out of PLAN_PENDING, cancel countdown
    useEffect(() => {
        if (phase !== 'PLAN_PENDING') {
            clearTimer();
        }
    }, [phase, clearTimer]);

    const handleConfirm = useCallback(() => {
        clearTimer();
        onConfirm();
    }, [clearTimer, onConfirm]);

    /**
     * Start the 5-second countdown. Called externally via the exported ref
     * when a confirm keyword is detected in the input.
     */
    const startCountdown = useCallback((chinese: boolean) => {
        setCountdownChinese(chinese);
        setCountdown(COUNTDOWN_SECONDS);
        if (timerRef.current) clearInterval(timerRef.current);
        timerRef.current = setInterval(() => {
            setCountdown((prev) => {
                if (prev === null) return null;
                if (prev <= 1) {
                    // Time's up — auto-confirm
                    clearTimer();
                    onConfirm();
                    return null;
                }
                return prev - 1;
            });
        }, 1000);
    }, [clearTimer, onConfirm]);

    const cancelCountdown = useCallback(() => {
        clearTimer();
    }, [clearTimer]);

    // Expose start/cancel via a stable ref on the component instance (parent will call via ref)
    // Instead, export as part of the module for ChatInput interception:
    // We'll use the store approach — attach startCountdown to window for simplicity,
    // or better: export a ref-holder. For cleanliness, we expose via a custom hook below.

    // Attach to window as a lightweight event bus for the countdown trigger
    useEffect(() => {
        const handler = (e: Event) => {
            const detail = (e as CustomEvent).detail as { chinese: boolean } | undefined;
            startCountdown(detail?.chinese ?? false);
        };
        window.addEventListener('kairo:startBuildCountdown', handler);
        return () => window.removeEventListener('kairo:startBuildCountdown', handler);
    }, [startCountdown]);

    useEffect(() => {
        const handler = () => cancelCountdown();
        window.addEventListener('kairo:cancelBuildCountdown', handler);
        return () => window.removeEventListener('kairo:cancelBuildCountdown', handler);
    }, [cancelCountdown]);

    if (!isVisible) return null;

    return (
        <div className="mt-3 mb-2 flex flex-col gap-2">
            {/* Action buttons */}
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

            {/* Countdown chip */}
            {countdown !== null && (
                <div className="flex items-center gap-2 px-3 py-1.5 text-xs rounded-md
                    bg-amber-500/10 text-amber-300 border border-amber-500/20 animate-pulse">
                    <span>
                        {countdownChinese
                            ? `${countdown} 秒后自动构建 — 输入或点击取消`
                            : `Auto-build in ${countdown}s — type or click to cancel`}
                    </span>
                    <button
                        onClick={cancelCountdown}
                        className="ml-auto p-0.5 rounded hover:bg-amber-500/20 transition-colors"
                        title="Cancel countdown"
                    >
                        <X size={12} />
                    </button>
                </div>
            )}
        </div>
    );
}
