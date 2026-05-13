import { useEffect, useRef, useState } from 'react';
import { MessageCircle, Bot, Users, Check, ChevronDown } from 'lucide-react';
import { useSessionModeStore, type SessionMode } from '@store/sessionModeStore';
import { useWorkspaceStore } from '@store/workspaceStore';
import { useExpertTeamStore } from '@store/expertTeamStore';
import { useSessionStore } from '@store/sessionStore';

interface ModeMeta {
    value: SessionMode;
    label: string;
    desc: string;
    icon: typeof MessageCircle;
    chip: string;
    chipText: string;
    disabled?: boolean;
    badge?: string;
}

const MODES: ModeMeta[] = [
    {
        value: 'chat',
        label: 'Chat',
        desc: 'Direct ReAct conversation',
        icon: MessageCircle,
        chip: 'bg-sky-500/10',
        chipText: 'text-sky-400',
    },
    {
        value: 'agent',
        label: 'Agent',
        desc: 'Single-agent autonomous task',
        icon: Bot,
        chip: 'bg-zinc-500/10',
        chipText: 'text-zinc-400',
        disabled: true,
        badge: '(coming soon)',
    },
    {
        value: 'experts',
        label: 'Experts',
        desc: 'Multi-expert team collaboration',
        icon: Users,
        chip: 'bg-violet-500/10',
        chipText: 'text-violet-400',
    },
];

interface SessionModeToggleProps {
    dropUp?: boolean;
}

export function SessionModeToggle({ dropUp = false }: SessionModeToggleProps) {
    const workspaceId = useWorkspaceStore((s) => s.currentWorkspaceId);
    const getMode = useSessionModeStore((s) => s.getMode);
    const setMode = useSessionModeStore((s) => s.setMode);
    const requestSessionRestart = useSessionModeStore((s) => s.requestSessionRestart);
    const activeTeam = useExpertTeamStore((s) => s.getActiveTeam());
    const activeSessionId = useSessionStore((s) => s.activeSessionId);

    const mode = workspaceId ? getMode(workspaceId) : 'chat';
    const teamRunning = activeTeam != null
        && (activeTeam.status === 'planning' || activeTeam.status === 'executing');

    const [open, setOpen] = useState(false);
    const ref = useRef<HTMLDivElement>(null);
    const current = MODES.find((m) => m.value === mode) ?? MODES[0];
    const Icon = current.icon;

    useEffect(() => {
        if (!open) return;
        const handler = (e: MouseEvent) => {
            if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
        };
        document.addEventListener('mousedown', handler);
        return () => document.removeEventListener('mousedown', handler);
    }, [open]);

    return (
        <div ref={ref} className="relative">
            <button
                onClick={() => !teamRunning && setOpen((o) => !o)}
                disabled={teamRunning}
                className={`flex items-center gap-1 px-1.5 py-1 rounded text-[11px] transition-colors text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)] disabled:opacity-50 disabled:cursor-not-allowed`}
                title={teamRunning
                    ? 'Cannot switch mode while team is active'
                    : `Mode: ${current.label} — ${current.desc}`}
                aria-label="Session mode"
            >
                <Icon size={12} className={current.chipText} />
                <span>{current.label}</span>
                <ChevronDown size={10} className="opacity-60" />
            </button>
            {open && (
                <div
                    className={`absolute left-0 w-60 rounded-md border border-[var(--border)] bg-[var(--bg-secondary)] shadow-xl z-50 py-1 text-xs ${
                        dropUp ? 'bottom-full mb-1' : 'mt-1'
                    }`}
                >
                    {MODES.map((m) => {
                        const ItemIcon = m.icon;
                        const active = m.value === mode;
                        const isDisabled = m.disabled === true;
                        return (
                            <button
                                key={m.value}
                                onClick={() => {
                                    if (!isDisabled && workspaceId) {
                                        const prevMode = getMode(workspaceId);
                                        setMode(workspaceId, m.value);
                                        setOpen(false);
                                        // If mode actually changed and there's an active session,
                                        // request session restart so App.tsx creates a new session
                                        if (m.value !== prevMode && activeSessionId) {
                                            requestSessionRestart(workspaceId);
                                        }
                                    }
                                }}
                                disabled={isDisabled}
                                className={`w-full flex items-start gap-2 px-3 py-2 text-left transition-colors ${
                                    isDisabled
                                        ? 'opacity-40 cursor-not-allowed'
                                        : 'hover:bg-[var(--bg-hover)]'
                                }`}
                            >
                                <ItemIcon size={13} className={`mt-0.5 ${m.chipText}`} />
                                <div className="flex-1 min-w-0">
                                    <div className="flex items-center justify-between">
                                        <span className={`font-medium ${isDisabled ? 'text-[var(--text-muted)]' : 'text-[var(--text-primary)]'}`}>
                                            {m.label}
                                            {m.badge && (
                                                <span className="ml-1 text-[9px] text-[var(--text-muted)] font-normal">
                                                    {m.badge}
                                                </span>
                                            )}
                                        </span>
                                        {active && <Check size={12} className="text-[var(--accent)]" />}
                                    </div>
                                    <div className="text-[10px] text-[var(--text-muted)] mt-0.5">{m.desc}</div>
                                </div>
                            </button>
                        );
                    })}
                </div>
            )}
        </div>
    );
}
