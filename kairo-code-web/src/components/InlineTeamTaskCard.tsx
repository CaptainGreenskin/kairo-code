import { useMemo, useCallback } from 'react';
import { useExpertTeamStore, type TeamState, type StepState, type StepPreview } from '../store/expertTeamStore';
import { ExpertStepCard } from './ExpertStepCard';
import { StepProgressBar } from './StepProgressBar';

// ── Role metadata ────────────────────────────────────────────────────────────

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

// ── Phase badge ──────────────────────────────────────────────────────────────

type TeamPhase = TeamState['status'];

const PHASE_COLORS: Record<TeamPhase, string> = {
  'planning': 'bg-blue-500/20 text-blue-400 border-blue-500/30',
  'plan-ready': 'bg-purple-500/20 text-purple-400 border-purple-500/30',
  'dispatching': 'bg-amber-500/20 text-amber-400 border-amber-500/30',
  'executing': 'bg-amber-500/20 text-amber-400 border-amber-500/30',
  'synthesizing': 'bg-cyan-500/20 text-cyan-400 border-cyan-500/30',
  'completed': 'bg-green-500/20 text-green-400 border-green-500/30',
  'failed': 'bg-red-500/20 text-red-400 border-red-500/30',
  'timeout': 'bg-orange-500/20 text-orange-400 border-orange-500/30',
};

function PhaseBadge({ phase }: { phase: TeamPhase }) {
  const colors = PHASE_COLORS[phase] ?? 'bg-gray-500/20 text-gray-400 border-gray-500/30';
  return (
    <span className={`text-[10px] font-semibold px-2 py-0.5 rounded-full uppercase border ${colors}`}>
      {phase}
    </span>
  );
}

// ── Step status indicator (used by PreviewStepRow) ──────────────────────────

function StepStatusIcon({ status }: { status: StepState['status'] | 'preview' }) {
  switch (status) {
    case 'pending':
    case 'preview':
      return <span className="text-gray-400">⏳</span>;
    case 'assigned':
    case 'thinking':
    case 'working':
      return <span className="text-blue-400 animate-pulse">▶</span>;
    case 'done':
      return <span className="text-green-400">✓</span>;
    case 'failed':
      return <span className="text-red-400">✗</span>;
    default:
      return <span className="text-gray-400">⏳</span>;
  }
}

// ── Preview step row (for plan-ready phase) ─────────────────────────────────

function PreviewStepRow({ step }: { step: StepPreview }) {
  const meta = getRoleMeta(step.roleId);
  return (
    <div className="flex items-center gap-2 py-1">
      <StepStatusIcon status="preview" />
      <span className="text-xs">{meta.icon}</span>
      <span className="text-xs font-medium text-[var(--text-primary)] min-w-[70px]">
        {step.roleName || meta.label}
      </span>
      <span className="text-[10px] text-[var(--text-muted)] truncate flex-1">
        {step.instruction.length > 60 ? step.instruction.slice(0, 60) + '…' : step.instruction}
      </span>
    </div>
  );
}

// ── Main InlineTeamTaskCard ──────────────────────────────────────────────────

export interface InlineTeamTaskCardProps {
  teamId: string;
  /** Opens the full ExpertTeamPanel. */
  onViewDag?: () => void;
  /** Sends confirmBuild WS action to start execution. */
  sendAction?: (payload: Record<string, unknown>) => boolean;
}

export function InlineTeamTaskCard({ teamId, onViewDag, sendAction }: InlineTeamTaskCardProps) {
  const team = useExpertTeamStore((state) => state.teams[teamId]);

  const handleStartTeam = useCallback(() => {
    if (sendAction) {
      sendAction({ action: 'confirmBuild', teamId });
    }
  }, [sendAction, teamId]);

  const handleStop = useCallback(() => {
    if (sendAction) {
      sendAction({ action: 'stopTeam', teamId });
    }
  }, [sendAction, teamId]);

  // Compute step list for the card
  const stepList = useMemo(() => {
    if (!team) return [];
    return Object.values(team.steps);
  }, [team]);

  if (!team) {
    return null;
  }

  const isPlanReady = team.status === 'plan-ready';
  const isExecuting = team.status === 'executing' || team.status === 'dispatching';
  const costText = team.cost.spent > 0 ? `$${team.cost.spent.toFixed(3)}` : '';

  return (
    <div className="my-2 rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] overflow-hidden max-w-[480px]">
      {/* Header */}
      <div className="flex items-center gap-2 px-3 py-2 border-b border-[var(--border)]">
        <span className="text-sm">🧠</span>
        <span className="text-xs font-semibold text-[var(--text-primary)] flex-1 truncate">
          {team.goal || 'Expert Team'}
        </span>
        <PhaseBadge phase={team.status} />
      </div>

      {/* Step progress bar */}
      {!isPlanReady && stepList.length > 0 && (
        <div className="px-3 pt-2">
          <StepProgressBar steps={stepList} teamPhase={team.status} />
        </div>
      )}

      {/* Step list */}
      <div className="px-3 py-2 space-y-2 max-h-[400px] overflow-y-auto">
        {/* Plan preview steps (plan-ready phase) */}
        {isPlanReady && team.planPreview && team.planPreview.steps.map((step) => (
          <PreviewStepRow key={step.stepId} step={step} />
        ))}

        {/* Executing steps */}
        {!isPlanReady && stepList.length > 0 && stepList.map((step) => (
          <ExpertStepCard
            key={step.stepId}
            step={step}
            defaultExpanded={step.status === 'thinking' || step.status === 'working'}
          />
        ))}

        {/* Planning placeholder */}
        {team.status === 'planning' && (
          <div className="flex items-center gap-2 py-2">
            <span className="text-xs text-[var(--text-muted)] animate-pulse">Planning team steps…</span>
          </div>
        )}

        {/* No steps yet during executing (DAG nodes not yet assigned) */}
        {!isPlanReady && stepList.length === 0 && team.status !== 'planning' && team.dag.length > 0 && (
          <div className="text-[10px] text-[var(--text-muted)]">
            {team.dag.length} steps planned
          </div>
        )}
      </div>

      {/* Footer: cost + buttons */}
      <div className="flex items-center justify-between px-3 py-2 border-t border-[var(--border)]">
        <span className="text-[10px] text-[var(--text-muted)]">{costText}</span>

        <div className="flex items-center gap-2">
          {/* View DAG button — always visible */}
          {onViewDag && (
            <button
              onClick={onViewDag}
              className="text-[11px] font-medium text-[var(--text-secondary)] hover:text-[var(--text-primary)]
                         px-2 py-1 rounded border border-[var(--border)] hover:bg-[var(--bg-hover)] transition-colors"
            >
              View DAG
            </button>
          )}

          {/* Start Team — only when plan-ready */}
          {isPlanReady && (
            <button
              onClick={handleStartTeam}
              className="text-[11px] font-semibold text-white
                         px-3 py-1 rounded bg-[var(--accent)] hover:opacity-90 transition-opacity"
            >
              Start Team
            </button>
          )}

          {/* Stop — only when executing */}
          {isExecuting && (
            <button
              onClick={handleStop}
              className="text-[11px] font-medium text-red-400
                         px-2 py-1 rounded border border-red-500/50 hover:bg-red-500/10 transition-colors"
            >
              Stop
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
