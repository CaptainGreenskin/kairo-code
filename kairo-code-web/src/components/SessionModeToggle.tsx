import { Bot, Users } from 'lucide-react';
import { useSessionModeStore, type SessionMode } from '@store/sessionModeStore';
import { useWorkspaceStore } from '@store/workspaceStore';

interface Props {
    disabled?: boolean;
}

const MODES: { value: SessionMode; label: string; icon: typeof Bot }[] = [
    { value: 'agent', label: 'Agent', icon: Bot },
    { value: 'experts', label: 'Experts', icon: Users },
];

export function SessionModeToggle({ disabled }: Props) {
    const workspaceId = useWorkspaceStore(s => s.currentWorkspaceId) ?? '';
    const mode = useSessionModeStore(s => s.getMode(workspaceId));
    const setMode = useSessionModeStore(s => s.setMode);

    const toggle = () => {
        if (disabled || !workspaceId) return;
        const next: SessionMode = mode === 'agent' ? 'experts' : 'agent';
        setMode(workspaceId, next);
    };

    const current = MODES.find(m => m.value === mode) ?? MODES[0];
    const Icon = current.icon;

    return (
        <button
            onClick={toggle}
            disabled={disabled}
            className={`flex items-center gap-1 px-3 py-1 rounded-[10px] text-xs font-semibold transition-all
                ${mode === 'experts'
                    ? 'text-white shadow-md'
                    : 'bg-[var(--bg-secondary)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)]'}
                disabled:opacity-40 disabled:cursor-not-allowed`}
            style={mode === 'experts' ? { background: 'linear-gradient(135deg, #6366f1, #8b5cf6)' } : undefined}
            title={`Mode: ${current.label}. Click to toggle.`}
        >
            <Icon size={13} />
            <span>{current.label}</span>
        </button>
    );
}
