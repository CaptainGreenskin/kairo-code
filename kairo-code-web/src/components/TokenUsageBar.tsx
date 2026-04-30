import { getContextWindow, getUsageRatio, getUsageColorClass, formatTokens } from '@utils/tokenBudget';
import { useState } from 'react';

interface TokenUsageBarProps {
    inputTokens: number;
    outputTokens: number;
    estimatedCost: number;
    model: string;
}

const COLOR_CLASSES = {
    green: 'bg-emerald-500',
    yellow: 'bg-amber-400',
    red: 'bg-red-500',
};

export function TokenUsageBar({ inputTokens, outputTokens, estimatedCost, model }: TokenUsageBarProps) {
    const [showTooltip, setShowTooltip] = useState(false);
    const total = inputTokens + outputTokens;
    if (total === 0) return null;

    const contextWindow = getContextWindow(model);
    const ratio = getUsageRatio(total, model);
    const color = getUsageColorClass(ratio);
    const pct = Math.round(ratio * 100);

    return (
        <div
            className="relative flex items-center gap-1.5 cursor-default"
            onMouseEnter={() => setShowTooltip(true)}
            onMouseLeave={() => setShowTooltip(false)}
        >
            {/* Compact bar + label */}
            <div className="flex items-center gap-1">
                <div className="w-16 h-1.5 rounded-full bg-[var(--bg-secondary)] overflow-hidden">
                    <div
                        className={`h-full rounded-full transition-all duration-500 ${COLOR_CLASSES[color]}`}
                        style={{ width: `${pct}%` }}
                    />
                </div>
                <span className="text-[10px] text-[var(--text-muted)] tabular-nums">
                    {formatTokens(total)}
                </span>
            </div>

            {/* Tooltip */}
            {showTooltip && (
                <div className="absolute bottom-full right-0 mb-2 z-50 min-w-[180px]
                    bg-[var(--bg-secondary)] border border-[var(--border)] rounded-lg shadow-lg
                    px-3 py-2 text-xs text-[var(--text-secondary)]">
                    <div className="font-medium text-[var(--text-primary)] mb-1.5">Token Usage</div>
                    <div className="space-y-1">
                        <div className="flex justify-between gap-4">
                            <span>Input</span>
                            <span className="tabular-nums text-[var(--text-primary)]">{formatTokens(inputTokens)}</span>
                        </div>
                        <div className="flex justify-between gap-4">
                            <span>Output</span>
                            <span className="tabular-nums text-[var(--text-primary)]">{formatTokens(outputTokens)}</span>
                        </div>
                        <div className="flex justify-between gap-4 pt-1 border-t border-[var(--border)]">
                            <span>Total</span>
                            <span className="tabular-nums text-[var(--text-primary)] font-medium">{formatTokens(total)}</span>
                        </div>
                        <div className="flex justify-between gap-4">
                            <span>Context</span>
                            <span className="tabular-nums">{pct}% of {formatTokens(contextWindow)}</span>
                        </div>
                        {estimatedCost > 0 && (
                            <div className="flex justify-between gap-4 pt-1 border-t border-[var(--border)]">
                                <span>Est. cost</span>
                                <span className="tabular-nums text-[var(--text-primary)]">
                                    ${estimatedCost < 0.001 ? '<$0.001' : estimatedCost.toFixed(3)}
                                </span>
                            </div>
                        )}
                    </div>
                </div>
            )}
        </div>
    );
}
