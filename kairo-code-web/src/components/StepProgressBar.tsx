import type { StepState } from '../store/expertTeamStore';

// ── Phase configuration ─────────────────────────────────────────────────────

type TeamPhase = 'planning' | 'plan-ready' | 'dispatching' | 'executing' | 'synthesizing' | 'completed' | 'failed' | 'timeout';

const PHASE_SEQUENCE: { key: TeamPhase[]; label: string }[] = [
  { key: ['planning', 'plan-ready'], label: 'Planning' },
  { key: ['dispatching', 'executing'], label: 'Executing' },
  { key: ['synthesizing'], label: 'Synthesizing' },
];

// ── StepProgressBar ─────────────────────────────────────────────────────────

export interface StepProgressBarProps {
  steps: StepState[];
  teamPhase: string;
}

export function StepProgressBar({ steps, teamPhase }: StepProgressBarProps) {
  const completed = steps.filter((s) => s.status === 'done').length;
  const total = steps.length;
  const percent = total > 0 ? Math.round((completed / total) * 100) : 0;

  const phase = teamPhase as TeamPhase;
  const isTerminal = phase === 'completed' || phase === 'failed' || phase === 'timeout';

  return (
    <div className="flex flex-col gap-1 py-1">
      {/* Phase dots + step counter */}
      <div className="flex items-center gap-1.5">
        {PHASE_SEQUENCE.map((entry, idx) => {
          const isActive = entry.key.includes(phase);
          const isPast =
            isTerminal ||
            PHASE_SEQUENCE.findIndex((e) => e.key.includes(phase)) > idx;

          return (
            <span key={entry.label} className="flex items-center gap-1.5">
              {idx > 0 && (
                <span className="text-[10px] text-[var(--text-muted)]">·</span>
              )}
              <span
                className={`text-[11px] ${
                  isActive
                    ? 'font-semibold text-blue-400'
                    : isPast
                      ? 'text-green-400'
                      : 'text-[var(--text-muted)]'
                }`}
              >
                {entry.label}
              </span>
            </span>
          );
        })}

        {/* Step counter */}
        {total > 0 && (
          <span className="ml-auto text-[10px] text-[var(--text-muted)]">
            ({completed}/{total} steps)
          </span>
        )}
      </div>

      {/* Progress bar */}
      <div className="h-1 w-full rounded-full bg-[var(--border)]">
        <div
          className={`h-1 rounded-full transition-all duration-300 ${
            phase === 'failed' || phase === 'timeout'
              ? 'bg-red-500'
              : phase === 'completed'
                ? 'bg-green-500'
                : 'bg-green-500'
          }`}
          style={{ width: `${isTerminal && phase === 'completed' ? 100 : percent}%` }}
        />
      </div>
    </div>
  );
}
