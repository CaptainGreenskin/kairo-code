import { useEffect, useRef } from 'react';

interface UseMobileAlertsOptions {
    pendingCount: number;
    isMobile: boolean;
}

// Tiny 200ms beep as base64 WAV (mono, 8kHz, 8-bit, ~1.6KB)
const BEEP_WAV = 'data:audio/wav;base64,UklGRlQGAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YTAGAACAf4B/gH+AgIB/gH+Af4CAgH+Af4B/gICAf4B/gH+AgIB/gH+Af4CAgH+Af4B/gIB/f39/f3+AgICAgICAgH9/f39/f4CAgICAgICAf39/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/gICAgICAgIB/f39/f3+AgICAgICAf39/f39/gICAgICAgIB/f39/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/f4CAgICAgIB/f39/f39/gICAgICAf39/f39/f4CAgICAgICAf39/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/f4CAgICAgICAf39/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/f4CAgICAgICAf39/f39/gICAgICAgIB/f39/f3+AgICAgICAf39/f39/f4CAgICAgICAgH9/f39/f4CAgICAgICAf39/f39/gICAgICAgIB/f39/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/f4CAgICAgICAf39/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/f4CAgICAgICAf39/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/f4CAgICAgICAf39/f39/gICAgICAgH9/f39/f3+AgICAgICAgIB/f39/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/f4CAgICAgICAf39/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/f4CAgICAgICAf39/f39/gICAgICAgIB/f39/f3+AgICAgICAf39/f39/f4CAgICAgICAf39/f39/f4CAgICAgICAf39/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/f4CAgICAgICAf39/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/f4CAgICAgICAf39/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/f4CAgICAgICAf39/f39/gICAgICAgIB/f39/f3+AgICAgICAf39/f39/f4CAgICAgICAgH9/f39/f4CAgICAgICAf39/f39/gICAgICAgIB/f39/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/f4CAgICAgICAf39/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/f4CAgICAgICAf39/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/f4CAgICAgICAf39/f39/gICAgICAgH9/f39/f3+AgICAgICAgIB/f39/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/f4CAgICAgICAf39/f39/gICAgICAgIB/f39/f3+AgICAgICAgH9/f39/f4CAgICAgICAf39/f39/gICAgICAgIB/f39/f3+AgICAgICAf39/f39/f4CAgICAgICA';

let audioCtx: AudioContext | null = null;
let audioElement: HTMLAudioElement | null = null;

function playBeep() {
    try {
        // Use HTMLAudioElement (works without user gesture in most mobile browsers for local HTTP)
        if (!audioElement) {
            audioElement = new Audio(BEEP_WAV);
            audioElement.volume = 0.5;
        }
        audioElement.currentTime = 0;
        audioElement.play().catch(() => {
            // Fallback: AudioContext beep
            if (!audioCtx) audioCtx = new AudioContext();
            const osc = audioCtx.createOscillator();
            const gain = audioCtx.createGain();
            osc.connect(gain);
            gain.connect(audioCtx.destination);
            osc.frequency.value = 880;
            gain.gain.value = 0.3;
            osc.start();
            osc.stop(audioCtx.currentTime + 0.15);
        });
    } catch {
        // silently ignore if audio not available
    }
}

function vibrate() {
    try {
        if (navigator.vibrate) {
            navigator.vibrate([200, 100, 200]);
        }
    } catch {
        // silently ignore
    }
}

/**
 * Mobile alert hook: triggers vibration, audio beep, and title flash
 * when pending approval count increases on mobile.
 */
export function useMobileAlerts({ pendingCount, isMobile }: UseMobileAlertsOptions) {
    const prevCountRef = useRef(0);
    const originalTitleRef = useRef(document.title);
    const titleIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

    // Trigger alert when pending count increases
    useEffect(() => {
        if (!isMobile) {
            prevCountRef.current = pendingCount;
            return;
        }

        if (pendingCount > prevCountRef.current && pendingCount > 0) {
            // New approval needed — alert the user
            vibrate();
            playBeep();
        }

        prevCountRef.current = pendingCount;
    }, [pendingCount, isMobile]);

    // Title flash when there are pending approvals
    useEffect(() => {
        if (!isMobile) return;

        if (pendingCount > 0) {
            if (!originalTitleRef.current || originalTitleRef.current.startsWith('⚠️')) {
                originalTitleRef.current = 'kairo-code';
            }
            let flash = true;
            titleIntervalRef.current = setInterval(() => {
                document.title = flash
                    ? `⚠️ (${pendingCount}) Approval needed`
                    : originalTitleRef.current;
                flash = !flash;
            }, 1200);
        } else {
            // Restore original title
            if (titleIntervalRef.current) {
                clearInterval(titleIntervalRef.current);
                titleIntervalRef.current = null;
            }
            document.title = originalTitleRef.current || 'kairo-code';
        }

        return () => {
            if (titleIntervalRef.current) {
                clearInterval(titleIntervalRef.current);
                titleIntervalRef.current = null;
            }
        };
    }, [pendingCount, isMobile]);
}
