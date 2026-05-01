import { CheckSquare, Square, X, Loader2 } from 'lucide-react';

interface PlanStep {
    text: string;
    done: boolean;
}

interface PlanPanelProps {
    steps: PlanStep[];
    isRunning: boolean;
    onClose: () => void;
    onClear: () => void;
}

export function PlanPanel({ steps, isRunning, onClose, onClear }: PlanPanelProps) {
    const collapsed = false;
    const doneCount = steps.filter(s => s.done).length;
    const total = steps.length;

    if (steps.length === 0) return null;

    return (
        <div className="fixed top-14 right-4 z-30 w-72 bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl shadow-xl overflow-hidden">
            {/* Header */}
            <div className="flex items-center justify-between px-3 py-2 bg-[var(--bg-primary)] border-b border-[var(--border)]">
                <div className="flex items-center gap-2">
                    {isRunning
                        ? <Loader2 size={13} className="text-[var(--accent)] animate-spin" />
                        : <CheckSquare size={13} className="text-emerald-400" />
                    }
                    <span className="text-xs font-semibold text-[var(--text-primary)]">
                        Plan ({doneCount}/{total})
                    </span>
                </div>
                <div className="flex items-center gap-1">
                    {!isRunning && (
                        <button
                            onClick={onClear}
                            className="p-0.5 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] transition-colors"
                            title="Clear plan"
                        >
                            <X size={12} />
                        </button>
                    )}
                    <button
                        onClick={onClose}
                        className="p-0.5 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] hover:text-red-400 transition-colors"
                    >
                        <X size={12} />
                    </button>
                </div>
            </div>

            {/* Progress bar */}
            <div className="h-0.5 bg-[var(--bg-hover)]">
                <div
                    className="h-full bg-[var(--accent)] transition-all duration-500"
                    style={{ width: `${total > 0 ? (doneCount / total) * 100 : 0}%` }}
                />
            </div>

            {/* Steps */}
            {!collapsed && (
                <div className="max-h-64 overflow-y-auto px-3 py-2 space-y-1.5">
                    {steps.map((step, i) => (
                        <div key={i} className="flex items-start gap-2">
                            {step.done
                                ? <CheckSquare size={13} className="text-emerald-400 shrink-0 mt-0.5" />
                                : <Square size={13} className="text-[var(--text-muted)] shrink-0 mt-0.5" />
                            }
                            <span className={`text-xs leading-relaxed ${
                                step.done
                                    ? 'text-[var(--text-muted)] line-through'
                                    : 'text-[var(--text-primary)]'
                            }`}>
                                {step.text}
                            </span>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
