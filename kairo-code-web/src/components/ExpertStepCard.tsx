import { useState } from 'react';
import { type StepState, type ToolCallEntry, deriveToolSummary } from '../store/expertTeamStore';
import { useThinkingTimer } from '../hooks/useThinkingTimer';
import { ToolCallCard } from './ToolCallCard';
import { LazyMarkdown } from './LazyMarkdown';
import type { ToolCall } from '../types/agent';

/** Adapt an expert-team ToolCallEntry to the main-chat ToolCall shape so we can reuse
 *  ToolCallCard (with its FileDiffView / TerminalOutput rendering). Expert tool calls are
 *  always already complete, so status is 'done'/'error' and no approval is required. */
function toToolCall(tc: ToolCallEntry, idx: number): ToolCall {
    return {
        id: `${tc.timestamp}-${idx}`,
        toolName: tc.toolName,
        input: tc.args ?? {},
        result: tc.result,
        status: tc.isError ? 'error' : 'done',
        requiresApproval: false,
        isError: tc.isError,
        durationMs: tc.durationMs,
    };
}

// ── Props ───────────────────────────────────────────────────────────────────

interface ExpertStepCardProps {
    step: StepState;
    defaultExpanded?: boolean;
}

// ── Role metadata ───────────────────────────────────────────────────────────

const ROLE_META: Record<string, { icon: string; label: string }> = {
    architect: { icon: '🏗️', label: 'Architect' },
    researcher: { icon: '🔍', label: 'Researcher' },
    coder: { icon: '💻', label: 'Coder' },
    reviewer: { icon: '👀', label: 'Reviewer' },
    tester: { icon: '🧪', label: 'Tester' },
    synthesizer: { icon: '📋', label: 'Synthesizer' },
};

function getRoleMeta(roleId: string) {
    return ROLE_META[roleId.toLowerCase()] ?? { icon: '⚙️', label: roleId };
}

// ── Status styling ──────────────────────────────────────────────────────────

const STATUS_STYLES: Record<StepState['status'], {
    border: string;
    badge: string;
    badgeText: string;
}> = {
    pending: {
        border: 'border-gray-500/30',
        badge: 'bg-gray-500/20 text-gray-400',
        badgeText: 'Pending',
    },
    assigned: {
        border: 'border-gray-500/30',
        badge: 'bg-gray-500/20 text-gray-400',
        badgeText: 'Assigned',
    },
    thinking: {
        border: 'border-blue-500/50 animate-pulse',
        badge: 'bg-blue-500/20 text-blue-400',
        badgeText: 'Thinking',
    },
    working: {
        border: 'border-blue-500/40',
        badge: 'bg-blue-500/20 text-blue-400',
        badgeText: 'Working',
    },
    done: {
        border: 'border-green-500/30',
        badge: 'bg-green-500/20 text-green-400',
        badgeText: '✓ Done',
    },
    failed: {
        border: 'border-red-500/40',
        badge: 'bg-red-500/20 text-red-400',
        badgeText: '✗ Failed',
    },
};

// ── Timeline entry components ───────────────────────────────────────────────

function ThinkingEntry({ duration, text }: { duration: string; text: string }) {
    return (
        <div className="flex items-start gap-2 py-1">
            <span className="text-xs shrink-0 mt-0.5">🧠</span>
            <div className="min-w-0 flex-1">
                <span className="text-xs font-medium text-[var(--text-secondary)]">
                    Thinking{duration ? ` (${duration})` : ''}
                </span>
                {text && (
                    <p className="text-[10px] text-[var(--text-muted)] italic mt-0.5 whitespace-pre-wrap break-words max-h-40 overflow-y-auto">
                        {text}
                    </p>
                )}
            </div>
        </div>
    );
}

function CompletionEntry({ status }: { status: 'done' | 'failed' }) {
    if (status === 'done') {
        return (
            <div className="flex items-center gap-2 py-1">
                <span className="text-xs text-green-400">✓</span>
                <span className="text-xs font-medium text-green-400">Completed</span>
            </div>
        );
    }
    return (
        <div className="flex items-center gap-2 py-1">
            <span className="text-xs text-red-400">✗</span>
            <span className="text-xs font-medium text-red-400">Failed</span>
        </div>
    );
}

// ── Summary line builder ────────────────────────────────────────────────────

function buildSummaryLine(step: StepState, thinkingTime: string): string {
    const parts: string[] = [];

    // Left: thinking duration
    const duration = step.thinkingDuration
        ? `${Math.round(step.thinkingDuration / 1000)}s`
        : thinkingTime;
    if (duration) {
        parts.push(`Thought · ${duration}`);
    }

    // Right: tool summary
    const summary = deriveToolSummary(step.toolCalls);
    const toolParts: string[] = [];
    if (summary.filesRead > 0) toolParts.push(`Read ${summary.filesRead}`);
    if (summary.filesWritten > 0) toolParts.push(`Write ${summary.filesWritten}`);
    if (summary.commandsRun > 0) toolParts.push(`Cmd ${summary.commandsRun}`);
    if (summary.searchesPerformed > 0) toolParts.push(`Search ${summary.searchesPerformed}`);

    if (toolParts.length > 0) {
        if (parts.length > 0) {
            parts.push(' | ');
        }
        parts.push(toolParts.join(' · '));
    }

    return parts.join('');
}

// ── Main component ──────────────────────────────────────────────────────────

export function ExpertStepCard({ step, defaultExpanded = false }: ExpertStepCardProps) {
    const [expanded, setExpanded] = useState(defaultExpanded);
    const thinkingTime = useThinkingTimer(step.thinkingStartedAt, step.status === 'thinking');

    const meta = getRoleMeta(step.roleId);
    const style = STATUS_STYLES[step.status];
    const summaryLine = buildSummaryLine(step, thinkingTime);
    const hasContent = step.status !== 'pending';

    // Thinking text for timeline
    const thinkingText = step.thinkingChunks.join('');
    const displayDuration = step.thinkingDuration
        ? `${Math.round(step.thinkingDuration / 1000)}s`
        : thinkingTime;

    return (
        <div className={`rounded-lg border ${style.border} bg-[var(--bg-secondary)] overflow-hidden`}>
            {/* Header — clickable to toggle */}
            <button
                onClick={() => hasContent && setExpanded(!expanded)}
                className={`w-full flex items-center gap-2 px-3 py-2 text-left
                           ${hasContent ? 'hover:bg-[var(--bg-hover)] cursor-pointer' : 'cursor-default'}
                           transition-colors`}
            >
                <span className="text-sm shrink-0">{meta.icon}</span>
                <span className="text-xs font-semibold text-[var(--text-primary)] min-w-[60px]">
                    {meta.label}
                </span>
                <span className="flex-1" />
                <span className={`text-[10px] font-semibold px-2 py-0.5 rounded-full ${style.badge}`}>
                    {style.badgeText}
                </span>
            </button>

            {/* Summary line (visible when collapsed and has content) */}
            {!expanded && summaryLine && (
                <div className="px-3 pb-2 -mt-0.5">
                    <span className="text-[10px] text-[var(--text-muted)]">{summaryLine}</span>
                </div>
            )}

            {/* Evaluation indicator */}
            {!expanded && step.evaluation && (
                <div className="px-3 pb-2">
                    <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded-full ${
                        step.evaluation.verdict === 'PASS'
                            ? 'bg-green-500/20 text-green-400'
                            : step.evaluation.verdict === 'FAIL'
                            ? 'bg-red-500/20 text-red-400'
                            : 'bg-amber-500/20 text-amber-400'
                    }`}>
                        {step.evaluation.verdict === 'PASS' ? '✓ Passed review' : 'Under review…'}
                    </span>
                </div>
            )}

            {/* Expanded timeline */}
            {expanded && hasContent && (
                <div className="px-3 pb-3 border-t border-[var(--border)]">
                    <div className="mt-2 space-y-0.5">
                        {/* Thinking entry */}
                        {thinkingText && (
                            <ThinkingEntry duration={displayDuration} text={thinkingText} />
                        )}

                        {/* Mid-flight user interventions (v2 steering): directives injected into
                            this running expert via Agent.injectMessages. */}
                        {step.steers && step.steers.length > 0 && (
                            <div className="space-y-1 py-1">
                                {step.steers.map((directive, i) => (
                                    <div
                                        key={`steer-${i}`}
                                        className="flex items-start gap-2 rounded border border-sky-500/30 bg-sky-500/10 px-2 py-1"
                                    >
                                        <span className="text-xs shrink-0 mt-0.5">👤</span>
                                        <p className="min-w-0 flex-1 text-[10px] text-sky-300">
                                            <span className="font-medium">User steered: </span>
                                            {directive}
                                        </p>
                                    </div>
                                ))}
                            </div>
                        )}

                        {/* Tool call cards — full args + result/diff/terminal, reusing the
                            main-chat ToolCallCard so experts show their real read/edit/bash work. */}
                        {step.toolCalls.length > 0 && (
                            <div className="space-y-1.5 py-1">
                                {step.toolCalls.map((tc, i) => (
                                    <ToolCallCard key={`${tc.timestamp}-${i}`} toolCall={toToolCall(tc, i)} />
                                ))}
                            </div>
                        )}

                        {/* Evaluation entry */}
                        {step.evaluation && (
                            <div className="flex items-start gap-2 py-1">
                                <span className="text-xs shrink-0 mt-0.5">
                                    {step.evaluation.verdict === 'PASS' ? '✅' : '⚠️'}
                                </span>
                                <div className="min-w-0 flex-1">
                                    <span className={`text-xs font-medium ${
                                        step.evaluation.verdict === 'PASS'
                                            ? 'text-green-400'
                                            : 'text-amber-400'
                                    }`}>
                                        Evaluation: {step.evaluation.verdict}
                                    </span>
                                    {step.evaluation.feedback && (
                                        <p className="text-[10px] text-[var(--text-muted)] mt-0.5 truncate">
                                            {step.evaluation.feedback.length > 80
                                                ? step.evaluation.feedback.slice(0, 80) + '…'
                                                : step.evaluation.feedback}
                                        </p>
                                    )}
                                </div>
                            </div>
                        )}

                        {/* Completion entry */}
                        {(step.status === 'done' || step.status === 'failed') && (
                            <CompletionEntry status={step.status} />
                        )}

                        {/* Artifact — the expert's final produced output/summary */}
                        {step.artifact && step.artifact.trim().length > 0 && (
                            <div className="mt-2 pt-2 border-t border-[var(--border)]">
                                <div className="text-[10px] font-semibold uppercase tracking-wide text-[var(--text-muted)] mb-1">
                                    Output
                                </div>
                                <div className="text-xs prose dark:prose-invert prose-sm max-w-none text-[var(--text-primary)] max-h-72 overflow-y-auto">
                                    <LazyMarkdown>{step.artifact}</LazyMarkdown>
                                </div>
                            </div>
                        )}
                    </div>
                </div>
            )}
        </div>
    );
}
