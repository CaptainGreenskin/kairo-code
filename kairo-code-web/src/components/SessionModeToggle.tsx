import { Bot, Users, Lock } from 'lucide-react';
import { useSessionModeStore, type SessionMode } from '@store/sessionModeStore';
import { useSessionStore } from '@store/sessionStore';

interface Props {
    disabled?: boolean;
}

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
    const isExpert = mode === 'experts';

    const setSessionMode = useSessionModeStore(s => s.setSessionMode);

    const toggle = () => {
        if (locked) return;
        const next: SessionMode = isExpert ? 'agent' : 'experts';
        setPendingMode(next);
        // If session already has a mode locked in, update it directly
        if (activeSessionId && sessionMode != null) {
            setSessionMode(activeSessionId, next);
        }
    };

    const Icon = isExpert ? Users : Bot;
    const label = isExpert ? 'Experts' : 'Agent';

    return (
        <button
            onClick={toggle}
            disabled={locked}
            className={`flex items-center gap-1.5 px-3 py-1 rounded-[10px] text-xs font-semibold transition-all
                ${isExpert
                    ? 'text-white shadow-md'
                    : 'bg-[var(--bg-secondary)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)]'}
                ${!locked ? 'cursor-pointer' : 'cursor-not-allowed opacity-50'}`}
            style={isExpert ? { background: 'linear-gradient(135deg, #6366f1, #8b5cf6)' } : undefined}
            title={locked
                ? `${label} 模式（已锁定，新建会话可切换）`
                : `当前：${label}，点击切换到 ${isExpert ? 'Agent' : 'Experts'}`}
        >
            <Icon size={13} />
            <span>{label}</span>
            {locked && <Lock size={10} className="ml-0.5 opacity-60" />}
        </button>
    );
}
