import { useState, useEffect } from 'react';

/**
 * Hook that provides a live-updating elapsed time string for thinking indicators.
 * Updates every second while active.
 *
 * @param startTime - epoch ms when thinking started (null if not started)
 * @param isActive - whether thinking is currently in progress
 * @returns formatted elapsed string like "8s", or empty string if not applicable
 */
export function useThinkingTimer(startTime: number | null, isActive: boolean): string {
    const [elapsed, setElapsed] = useState(0);

    useEffect(() => {
        if (!startTime || !isActive) {
            return;
        }
        // Set initial value immediately
        setElapsed(Math.floor((Date.now() - startTime) / 1000));

        const interval = setInterval(() => {
            setElapsed(Math.floor((Date.now() - startTime) / 1000));
        }, 1000);

        return () => clearInterval(interval);
    }, [startTime, isActive]);

    if (!startTime) return '';
    if (isActive) return `${elapsed}s`;
    // When no longer active, return final duration
    return `${Math.floor((Date.now() - startTime) / 1000)}s`;
}
