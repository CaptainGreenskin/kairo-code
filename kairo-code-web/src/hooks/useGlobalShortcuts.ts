import { useEffect } from 'react';
import type { Message } from '@/types/agent';
import type { ToastMessage } from '@components/Toast';

export interface UseGlobalShortcutsArgs {
    sessionId: string | null;
    messages: Message[];
    sidebarSessions: Array<{ sessionId: string }>;
    sortedSessions: Array<{ id: string }>;
    setShowMessageSearch: (fn: (v: boolean) => boolean) => void;
    setShowSessionSearch: (v: boolean) => void;
    setShowCommandPalette: (fn: (v: boolean) => boolean) => void;
    toggleShell: () => void;
    setShowShortcuts: (fn: (v: boolean) => boolean) => void;
    handleNewSession: () => void;
    handleDeleteSession: (sid: string) => void;
    handleSelectSession: (sid: string) => void;
    handleCopyConversation: () => void;
    handleApproveTool: (toolCallId: string, approved: boolean) => void;
    addToast: (type: ToastMessage['type'], message: string) => void;
}

function isTypingTarget(e: KeyboardEvent): boolean {
    const t = e.target as HTMLElement | null;
    if (!t) return false;
    const tag = t.tagName;
    return tag === 'INPUT' || tag === 'TEXTAREA' || t.isContentEditable;
}

/**
 * Single global keyboard shortcut handler. Replaces five separate keydown
 * useEffects in App.tsx with one consolidated listener.
 */
export function useGlobalShortcuts(args: UseGlobalShortcutsArgs): void {
    const {
        sessionId, messages, sidebarSessions, sortedSessions,
        setShowMessageSearch, setShowSessionSearch, setShowCommandPalette,
        toggleShell, setShowShortcuts,
        handleNewSession, handleDeleteSession, handleSelectSession,
        handleCopyConversation, handleApproveTool, addToast,
    } = args;

    useEffect(() => {
        const handler = (e: KeyboardEvent) => {
            const typing = isTypingTarget(e);
            const mod = e.metaKey || e.ctrlKey;

            // Cmd+F → in-message search (skip if typing)
            if (mod && !e.shiftKey && e.key === 'f') {
                if (!typing) {
                    e.preventDefault();
                    setShowMessageSearch(v => !v);
                }
                return;
            }
            // Cmd+Shift+F → session search
            if (mod && e.shiftKey && e.key === 'f') {
                e.preventDefault();
                setShowSessionSearch(true);
                return;
            }
            // Cmd+K → command palette
            if (mod && !e.shiftKey && e.key === 'k') {
                e.preventDefault();
                setShowCommandPalette(v => !v);
                return;
            }
            // Cmd+` → toggle bottom panel (shell)
            if (mod && e.key === '`') {
                e.preventDefault();
                toggleShell();
                return;
            }
            // ? → shortcuts modal (skip if typing)
            if (e.key === '?' && !typing && !mod) {
                e.preventDefault();
                setShowShortcuts(v => !v);
                return;
            }

            // Session-management shortcuts (skip if typing)
            if (typing) return;

            // Cmd+N → new session
            if (mod && e.key === 'n' && !e.shiftKey) {
                e.preventDefault();
                handleNewSession();
                return;
            }
            // Cmd+W → close session
            if (mod && e.key === 'w' && sessionId) {
                e.preventDefault();
                handleDeleteSession(sessionId);
                return;
            }
            // Cmd+1-9 → switch to nth session
            if (mod && /^[1-9]$/.test(e.key)) {
                const idx = parseInt(e.key, 10) - 1;
                if (idx < sidebarSessions.length) {
                    e.preventDefault();
                    handleSelectSession(sidebarSessions[idx].sessionId);
                }
                return;
            }
            // Cmd+[ → previous session
            if (mod && e.key === '[') {
                e.preventDefault();
                const idx = sortedSessions.findIndex(s => s.id === sessionId);
                if (idx > 0) handleSelectSession(sortedSessions[idx - 1].id);
                return;
            }
            // Cmd+] → next session
            if (mod && e.key === ']') {
                e.preventDefault();
                const idx = sortedSessions.findIndex(s => s.id === sessionId);
                if (idx >= 0 && idx < sortedSessions.length - 1) {
                    handleSelectSession(sortedSessions[idx + 1].id);
                }
                return;
            }
            // Cmd+Shift+C → copy conversation
            if (mod && e.shiftKey && e.key === 'c') {
                e.preventDefault();
                handleCopyConversation();
                return;
            }

            // y/n → approve/reject first pending tool call (no modifiers)
            if (!mod && !e.altKey && (e.key === 'y' || e.key === 'n')) {
                let pendingToolCallId: string | null = null;
                for (const msg of messages) {
                    const pending = msg.toolCalls?.find(tc => tc.status === 'pending');
                    if (pending) {
                        pendingToolCallId = pending.id;
                        break;
                    }
                }
                if (!pendingToolCallId) return;
                e.preventDefault();
                const approved = e.key === 'y';
                handleApproveTool(pendingToolCallId, approved);
                addToast(approved ? 'success' : 'info', approved ? 'Tool approved' : 'Tool rejected');
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [
        sessionId, messages, sidebarSessions, sortedSessions,
        setShowMessageSearch, setShowSessionSearch, setShowCommandPalette,
        toggleShell, setShowShortcuts,
        handleNewSession, handleDeleteSession, handleSelectSession,
        handleCopyConversation, handleApproveTool, addToast,
    ]);
}
