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
            className={`flex items-center gap-1 px-2 py-1 rounded text-xs font-medium transition-colors
                ${mode === 'experts'
                    ? 'bg-purple-500/15 text-purple-400 hover:bg-purple-500/25'
                    : 'bg-[var(--bg-secondary)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-tertiary)]'}
                disabled:opacity-40 disabled:cursor-not-allowed`}
            title={`Mode: ${current.label}. Click to toggle.`}
        >
            <Icon size={13} />
            <span>{current.label}</span>
        </button>
    );
}
