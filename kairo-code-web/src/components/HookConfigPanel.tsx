import { useEffect, useState } from 'react';
import { Zap, ZapOff, X, RefreshCw, ToggleLeft, ToggleRight } from 'lucide-react';

interface HookInfo {
    name: string;
    description: string;
    enabled: boolean;
}

interface HookConfigPanelProps {
    onClose: () => void;
}

export function HookConfigPanel({ onClose }: HookConfigPanelProps) {
    const [hooks, setHooks] = useState<HookInfo[]>([]);
    const [loading, setLoading] = useState(false);
    const [toggling, setToggling] = useState<string | null>(null);

    const refresh = async () => {
        setLoading(true);
        try {
            const res = await fetch('/api/hooks');
            if (res.ok) setHooks(await res.json());
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => { refresh(); }, []);

    const toggle = async (name: string) => {
        setToggling(name);
        try {
            const res = await fetch(`/api/hooks/${name}/toggle`, { method: 'POST' });
            if (res.ok) {
                const updated: HookInfo = await res.json();
                setHooks(prev => prev.map(h => h.name === name ? updated : h));
            }
        } finally {
            setToggling(null);
        }
    };

    const enabledCount = hooks.filter(h => h.enabled).length;

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm" onClick={onClose}>
            <div
                className="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl shadow-2xl w-full max-w-xl max-h-[80vh] flex flex-col overflow-hidden"
                onClick={e => e.stopPropagation()}
            >
                {/* Header */}
                <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--border)]">
                    <div className="flex items-center gap-2">
                        <Zap size={14} className="text-[var(--accent)]" />
                        <span className="text-sm font-semibold text-[var(--text-primary)]">Hook Configuration</span>
                        <span className="text-xs text-[var(--text-muted)] bg-[var(--bg-hover)] px-1.5 py-0.5 rounded-full">
                            {enabledCount}/{hooks.length} enabled
                        </span>
                    </div>
                    <div className="flex items-center gap-1">
                        <button onClick={refresh} disabled={loading}
                            className="p-1 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] transition-colors">
                            <RefreshCw size={12} className={loading ? 'animate-spin' : ''} />
                        </button>
                        <button onClick={onClose}
                            className="p-1 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] hover:text-red-400 transition-colors">
                            <X size={13} />
                        </button>
                    </div>
                </div>

                {/* Hook list */}
                <div className="flex-1 overflow-y-auto divide-y divide-[var(--border)]">
                    {hooks.map(hook => (
                        <div key={hook.name}
                            className={`flex items-center gap-3 px-4 py-3 hover:bg-[var(--bg-hover)] transition-colors ${
                                !hook.enabled ? 'opacity-50' : ''
                            }`}>
                            {hook.enabled
                                ? <Zap size={13} className="text-[var(--accent)] shrink-0" />
                                : <ZapOff size={13} className="text-[var(--text-muted)] shrink-0" />
                            }
                            <div className="flex-1 min-w-0">
                                <p className="text-xs font-mono text-[var(--text-primary)] truncate">{hook.name}</p>
                                <p className="text-xs text-[var(--text-muted)] mt-0.5 leading-relaxed">{hook.description}</p>
                            </div>
                            <button
                                onClick={() => toggle(hook.name)}
                                disabled={toggling === hook.name}
                                className="shrink-0 transition-colors"
                                title={hook.enabled ? 'Disable' : 'Enable'}
                            >
                                {hook.enabled
                                    ? <ToggleRight size={20} className="text-[var(--accent)]" />
                                    : <ToggleLeft size={20} className="text-[var(--text-muted)]" />
                                }
                            </button>
                        </div>
                    ))}
                </div>

                <div className="px-4 py-2 border-t border-[var(--border)] bg-[var(--bg-primary)]">
                    <p className="text-xs text-[var(--text-muted)]">
                        Changes saved to <code className="font-mono">.kairo-code/hooks.json</code> — effective on next session.
                    </p>
                </div>
            </div>
        </div>
    );
}
