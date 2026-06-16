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

    const locked = messages.length > 0 && sessionMode != null;
    const mode: SessionMode = sessionMode ?? pendingMode;

    return (
        <div className="flex items-center gap-0.5 bg-[var(--bg-secondary)] rounded-[10px] p-0.5">
            {MODES.map(({ value, label, icon: Icon }) => {
                const active = mode === value;
                return (
                    <button
                        key={value}
                        onClick={() => {
                            if (!disabled && !locked) setPendingMode(value);
                        }}
                        disabled={disabled || locked}
                        className={`flex items-center gap-1 px-2.5 py-1 rounded-lg text-xs font-medium transition-all
                            ${active
                                ? value === 'experts'
                                    ? 'text-white shadow-sm'
                                    : 'bg-[var(--bg-primary)] text-[var(--text-primary)] shadow-sm'
                                : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]'}
                            disabled:opacity-40 disabled:cursor-not-allowed`}
                        style={active && value === 'experts' ? { background: 'linear-gradient(135deg, #6366f1, #8b5cf6)' } : undefined}
                        title={locked
                            ? `${label} (锁定 — 新建会话可切换)`
                            : label}
                    >
                        <Icon size={12} />
                        <span>{label}</span>
                    </button>
                );
            })}
            {locked && <Lock size={9} className="text-[var(--text-muted)] ml-0.5" />}
        </div>
    );
}
