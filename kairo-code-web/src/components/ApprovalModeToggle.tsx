import { useEffect, useRef, useState } from 'react';
import { Shield, ShieldAlert, ShieldOff, Check, ChevronDown } from 'lucide-react';
import { usePreferencesStore, type ApprovalMode } from '@store/preferencesStore';

interface ModeMeta {
    value: ApprovalMode;
    label: string;
    desc: string;
    icon: typeof Shield;
    chip: string;       // chip background (tailwind)
    chipText: string;   // chip text colour
}

const MODES: ModeMeta[] = [
    {
        value: 'manual',
        label: 'Manual',
        desc: 'Prompt for every risky tool',
        icon: Shield,
        chip: 'bg-emerald-500/10',
        chipText: 'text-emerald-400',
    },
    {
        value: 'auto-safe',
        label: 'Auto-safe',
        desc: 'Auto-approve writes, gate bash',
        icon: ShieldAlert,
        chip: 'bg-amber-500/10',
        chipText: 'text-amber-400',
    },
    {
        value: 'yolo',
        label: 'YOLO',
        desc: 'Auto-approve everything',
        icon: ShieldOff,
        chip: 'bg-rose-500/10',
        chipText: 'text-rose-400',
    },
];

interface ApprovalModeToggleProps {
    /** Pop the dropdown above the trigger (true) instead of below. Use when rendered
     *  in a footer that's anchored to the viewport bottom — the chat-input toolbar. */
    dropUp?: boolean;
}

/**
 * Compact dropdown next to the model selector that switches the tool-call
 * approval policy. Persisted via {@link usePreferencesStore}.
 */
export function ApprovalModeToggle({ dropUp = false }: ApprovalModeToggleProps = {}) {
    const mode = usePreferencesStore((s) => s.approvalMode);
    const setMode = usePreferencesStore((s) => s.setApprovalMode);
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
                onClick={() => setOpen((o) => !o)}
                className={`flex items-center gap-1 px-1.5 py-1 rounded text-[11px] transition-colors text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)]`}
                title={`Approval: ${current.label} — ${current.desc}`}
                aria-label="Approval mode"
            >
                <Icon size={12} className={current.chipText} />
                <span>{current.label}</span>
                <ChevronDown size={10} className="opacity-60" />
            </button>
            {open && (
                <div
                    className={`absolute right-0 w-56 rounded-md border border-[var(--border)] bg-[var(--bg-secondary)] shadow-xl z-50 py-1 text-xs ${
                        dropUp ? 'bottom-full mb-1' : 'mt-1'
                    }`}
                >
                    {MODES.map((m) => {
                        const ItemIcon = m.icon;
                        const active = m.value === mode;
                        return (
                            <button
                                key={m.value}
                                onClick={() => {
                                    setMode(m.value);
                                    setOpen(false);
                                }}
                                className="w-full flex items-start gap-2 px-3 py-2 text-left hover:bg-[var(--bg-hover)] transition-colors"
                            >
                                <ItemIcon size={13} className={`mt-0.5 ${m.chipText}`} />
                                <div className="flex-1 min-w-0">
                                    <div className="flex items-center justify-between">
                                        <span className="font-medium text-[var(--text-primary)]">{m.label}</span>
                                        {active && <Check size={12} className="text-[var(--accent)]" />}
                                    </div>
                                    <div className="text-[10px] text-[var(--text-muted)] mt-0.5">{m.desc}</div>
                                </div>
                            </button>
                        );
                    })}
                    <div className="border-t border-[var(--border)] mt-1 pt-1.5 px-3 pb-1 text-[10px] text-[var(--text-muted)]">
                        Tip: YOLO disables every approval gate — only use in throwaway workspaces.
                    </div>
                </div>
            )}
        </div>
    );
}
