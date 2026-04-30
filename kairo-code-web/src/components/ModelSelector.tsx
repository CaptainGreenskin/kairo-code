import { useState, useRef, useEffect } from 'react';
import { ChevronDown } from 'lucide-react';

interface ModelSelectorProps {
    models: string[];
    currentModel: string | null;
    onChange: (model: string) => void;
    disabled?: boolean;
}

export function ModelSelector({ models, currentModel, onChange, disabled }: ModelSelectorProps) {
    const [open, setOpen] = useState(false);
    const ref = useRef<HTMLDivElement>(null);

    useEffect(() => {
        const handler = (e: MouseEvent) => {
            if (!ref.current?.contains(e.target as Node)) setOpen(false);
        };
        document.addEventListener('mousedown', handler);
        return () => document.removeEventListener('mousedown', handler);
    }, []);

    if (!models.length) return null;

    // Show short name (strip provider prefix)
    const shortName = (m: string) => m.split('/').pop() ?? m;

    return (
        <div ref={ref} className="relative">
            <button
                onClick={() => !disabled && setOpen(v => !v)}
                disabled={disabled}
                className="flex items-center gap-1 px-2 py-1 text-xs rounded text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-secondary)] transition-colors disabled:opacity-50 disabled:cursor-not-allowed font-mono"
                title={currentModel ?? 'Select model'}
            >
                <span>{currentModel ? shortName(currentModel) : 'model'}</span>
                <ChevronDown size={12} />
            </button>

            {open && (
                <div className="absolute top-full right-0 mt-1 py-1 min-w-[200px] rounded-lg shadow-lg bg-[var(--bg-primary)] border border-[var(--border)] z-50">
                    {models.map(m => (
                        <button
                            key={m}
                            onClick={() => { onChange(m); setOpen(false); }}
                            className={`w-full text-left px-3 py-1.5 text-xs hover:bg-[var(--bg-secondary)] transition-colors font-mono truncate ${
                                m === currentModel ? 'text-[var(--accent)]' : 'text-[var(--text-primary)]'
                            }`}
                        >
                            {m}
                        </button>
                    ))}
                </div>
            )}
        </div>
    );
}
