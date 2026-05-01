import { useEffect, useRef, useCallback } from 'react';
import { notifyAgent, setTitleBadge, requestNotificationPermission } from '@utils/agentNotification';

interface UseAgentNotificationOptions {
    isRunning: boolean;
    pendingApprovalCount: number;
    hasError: boolean;
    isThinking: boolean;
}

/**
 * Manages browser notifications and title badge for agent state changes.
 */
export function useAgentNotification({
    isRunning,
    pendingApprovalCount,
    hasError,
    isThinking,
}: UseAgentNotificationOptions): void {
    const prevRunningRef = useRef(isRunning);
    const prevPendingRef = useRef(pendingApprovalCount);
    const permissionRequestedRef = useRef(false);

    // Request permission once on mount
    useEffect(() => {
        if (permissionRequestedRef.current) return;
        permissionRequestedRef.current = true;
        requestNotificationPermission();
    }, []);

    // Notify on agent done (isRunning flips false) or error
    useEffect(() => {
        const wasRunning = prevRunningRef.current;
        prevRunningRef.current = isRunning;

        if (wasRunning && !isRunning) {
            if (hasError) {
                notifyAgent('error');
            } else {
                notifyAgent('done');
            }
        }
    }, [isRunning, hasError]);

    // Notify on new pending approval
    useEffect(() => {
        const prevPending = prevPendingRef.current;
        prevPendingRef.current = pendingApprovalCount;

        if (pendingApprovalCount > prevPending && pendingApprovalCount > 0) {
            notifyAgent('approval');
        }
    }, [pendingApprovalCount]);

    // Update title badge
    useEffect(() => {
        const badge = pendingApprovalCount > 0
            ? pendingApprovalCount
            : (isRunning || isThinking) ? 1 : 0;
        setTitleBadge(badge);
    }, [pendingApprovalCount, isRunning, isThinking]);

    // Clear badge when tab becomes visible
    const handleVisibilityChange = useCallback(() => {
        if (!document.hidden && pendingApprovalCount === 0 && !isRunning && !isThinking) {
            setTitleBadge(0);
        }
    }, [pendingApprovalCount, isRunning, isThinking]);

    useEffect(() => {
        document.addEventListener('visibilitychange', handleVisibilityChange);
        return () => document.removeEventListener('visibilitychange', handleVisibilityChange);
    }, [handleVisibilityChange]);

    // Cleanup badge on unmount
    useEffect(() => {
        return () => setTitleBadge(0);
    }, []);
}
