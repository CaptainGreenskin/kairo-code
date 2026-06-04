import { Bot, Users, Lock } from 'lucide-react';
import { useSessionModeStore, type SessionMode } from '@store/sessionModeStore';
import { useSessionStore } from '@store/sessionStore';

interface Props {
    disabled?: boolean;
}

const MODES: { value: SessionMode; label: string; icon: typeof Bot }[] = [
    { value: 'agent', label: 'Agent', icon: Bot },
    { value: 'experts', label: 'Experts', icon: Users },
];

export function SessionModeToggle({ disabled }: Props) {
    const activeSessionId = useSessionStore(s => s.activeSessionId);
    const sessionMode = useSessionModeStore(s => activeSessionId ? s.getSessionMode(activeSessionId) : null);
    const pendingMode = useSessionModeStore(s => s.pendingMode);
    const setPendingMode = useSessionModeStore(s => s.setPendingMode);

    const messages = useSessionStore(s => {
        if (!activeSessionId) return [];
        return s.sessions[activeSessionId]?.messages ?? [];
    });

    // Session already has messages → mode is locked (was fixed at session creation)
    const locked = messages.length > 0 && sessionMode != null;

    // Display mode: locked session mode > pending mode for new sessions
    const mode: SessionMode = sessionMode ?? pendingMode;

    const toggle = () => {
        if (disabled || locked) return;
        const next: SessionMode = mode === 'agent' ? 'experts' : 'agent';
        setPendingMode(next);
    };

    const current = MODES.find(m => m.value === mode) ?? MODES[0];
    const Icon = current.icon;

    return (
        <button
            onClick={toggle}
            disabled={disabled || locked}
            className={`flex items-center gap-1 px-3 py-1 rounded-[10px] text-xs font-semibold transition-all
                ${mode === 'experts'
                    ? 'text-white shadow-md'
                    : 'bg-[var(--bg-secondary)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)]'}
                disabled:opacity-40 disabled:cursor-not-allowed`}
            style={mode === 'experts' ? { background: 'linear-gradient(135deg, #6366f1, #8b5cf6)' } : undefined}
            title={locked
                ? `Mode: ${current.label} (locked — create a new chat to change)`
                : `Mode: ${current.label}. Click to toggle.`}
        >
            <Icon size={13} />
            <span>{current.label}</span>
            {locked && <Lock size={10} className="ml-0.5 opacity-60" />}
        </button>
    );
}
