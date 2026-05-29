import { useCallback, useState } from 'react';
import { ExternalLink } from 'lucide-react';
import { useExpertTeamStore, TeamState, StepState, deriveToolSummary } from '../store/expertTeamStore';
import { useOpenFilesStore } from '../store/openFilesStore';
import { RejectFeedbackModal } from './RejectFeedbackModal';
import { SynthesizerCard } from './SynthesizerCard';
import { useThinkingTimer } from '../hooks/useThinkingTimer';

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

// ── Status colors ────────────────────────────────────────────────────────────

const STATUS_COLORS: Record<string, { bg: string; text: string }> = {
  pending: { bg: 'bg-gray-500/15', text: 'text-gray-400' },
  assigned: { bg: 'bg-blue-500/15', text: 'text-blue-400' },
  thinking: { bg: 'bg-blue-400/15', text: 'text-blue-300' },
  working: { bg: 'bg-amber-500/15', text: 'text-amber-400' },
  done: { bg: 'bg-green-500/15', text: 'text-green-400' },
  failed: { bg: 'bg-red-500/15', text: 'text-red-400' },
};

function getStatusColors(status: string) {
  return STATUS_COLORS[status] ?? STATUS_COLORS.pending;
}

// ── TopBar ───────────────────────────────────────────────────────────────────

function TopBar({ team }: { team: TeamState }) {
  const steps = Object.values(team.steps);
  const total = team.dag.length || steps.length;
  const completed = steps.filter(s => s.status === 'done').length;
  const failed = steps.filter(s => s.status === 'failed').length;

  const costPct = team.cost.budget > 0
    ? Math.min((team.cost.spent / team.cost.budget) * 100, 100)
    : 0;

  const teamStatusColor: Record<string, string> = {
    planning: 'bg-blue-500/20 text-blue-400',
    executing: 'bg-amber-500/20 text-amber-400',
    completed: 'bg-green-500/20 text-green-400',
    failed: 'bg-red-500/20 text-red-400',
    timeout: 'bg-orange-500/20 text-orange-400',
  };

  return (
    <div className="flex items-center justify-between px-4 py-2 border-b border-[var(--border)]
                    bg-[var(--bg-secondary)] shrink-0">
      <div className="flex items-center gap-4">
        <span className={`text-[10px] font-medium px-2 py-0.5 rounded-full uppercase
          ${teamStatusColor[team.status] ?? 'bg-gray-500/20 text-gray-400'}`}>
          {team.status}
        </span>
        <span className="text-xs text-[var(--text-secondary)]">
          Progress: <span className="font-medium text-[var(--text-primary)]">{completed}/{total}</span>
          {failed > 0 && <span className="text-red-400 ml-1">({failed} failed)</span>}
        </span>
      </div>
      {team.cost.budget > 0 && (
        <div className="flex items-center gap-2">
          <span className="text-xs text-[var(--text-muted)]">Cost:</span>
          <div className="w-20 h-1.5 rounded-full bg-[var(--bg-primary)] overflow-hidden">
            <div
              className={`h-full rounded-full transition-all duration-300 ${
                costPct > 80 ? 'bg-red-500' : costPct > 50 ? 'bg-amber-500' : 'bg-green-500'
              }`}
              style={{ width: `${costPct}%` }}
            />
          </div>
          <span className="text-[10px] text-[var(--text-muted)]">
            ${team.cost.spent.toFixed(2)}/${team.cost.budget.toFixed(2)}
          </span>
        </div>
      )}
    </div>
  );
}

// ── Roster row ───────────────────────────────────────────────────────────────

interface RosterEntry {
  stepId: string;
  roleId: string;
  instruction: string;
}

function rosterStatusText(step: StepState | undefined, thinkingTime: string): string | null {
  if (!step) return null;
  if (step.status === 'thinking') return `Thinking · ${thinkingTime || '0s'}`;
  if (step.status === 'working') {
    const s = deriveToolSummary(step.toolCalls);
    const parts: string[] = [];
    if (s.filesRead > 0) parts.push(`Read ${s.filesRead}`);
    if (s.filesWritten > 0) parts.push(`Write ${s.filesWritten}`);
    if (s.commandsRun > 0) parts.push(`Cmd ${s.commandsRun}`);
    if (s.searchesPerformed > 0) parts.push(`Search ${s.searchesPerformed}`);
    return parts.length > 0 ? parts.join(' · ') : 'Working…';
  }
  if (step.status === 'done') {
    const dur = step.thinkingDuration ? ` · ${Math.round(step.thinkingDuration / 1000)}s` : '';
    const s = deriveToolSummary(step.toolCalls);
    const n = s.filesRead + s.filesWritten + s.commandsRun + s.searchesPerformed;
    return `${n} tool${n === 1 ? '' : 's'}${dur}`;
  }
  return null;
}

function RosterRow({ entry, step, onOpen }: {
  entry: RosterEntry;
  step: StepState | undefined;
  onOpen: () => void;
}) {
  const meta = getRoleMeta(entry.roleId);
  const status = step?.status ?? 'pending';
  const colors = getStatusColors(status);
  const active = status === 'thinking' || status === 'working';
  const thinkingTime = useThinkingTimer(step?.thinkingStartedAt ?? null, status === 'thinking');
  const detail = rosterStatusText(step, thinkingTime);

  return (
    <button
      onClick={onOpen}
      className="group w-full flex items-center gap-2.5 px-3 py-2.5 text-left rounded-lg
                 border border-[var(--border)] bg-[var(--bg-secondary)]
                 hover:border-[var(--accent)] hover:bg-[var(--bg-hover)] transition-colors"
      title="Open full execution in a tab"
    >
      <span className={`text-base shrink-0 ${active ? 'animate-pulse' : ''}`}>{meta.icon}</span>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold text-[var(--text-primary)]">{meta.label}</span>
          <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded-full capitalize ${colors.bg} ${colors.text}`}>
            {status}
          </span>
        </div>
        {(detail || entry.instruction) && (
          <div className="text-[10px] text-[var(--text-muted)] truncate mt-0.5">
            {detail ?? entry.instruction}
          </div>
        )}
      </div>
      <ExternalLink size={13} className="shrink-0 text-[var(--text-muted)] opacity-0 group-hover:opacity-100 transition-opacity" />
    </button>
  );
}

// ── Main component ───────────────────────────────────────────────────────────

export interface ExpertTeamPanelProps {
  teamId: string;
  readOnly?: boolean;
  /** Send a JSON payload over the shared WebSocket. */
  sendAction?: (payload: Record<string, unknown>) => boolean;
  /** Called when user clicks Replay on the SynthesizerCard. */
  onReplay?: () => void;
}

export function ExpertTeamPanel({ teamId, readOnly: _readOnly = false, sendAction, onReplay }: ExpertTeamPanelProps) {
  const team = useExpertTeamStore(state => state.teams[teamId]);
  const openExpertStepTab = useOpenFilesStore(s => s.openExpertStepTab);
  const [rejectModal, setRejectModal] = useState<{ visible: boolean; stepId: string; roleName: string }>({
    visible: false, stepId: '', roleName: '',
  });

  const handleRejectSubmit = useCallback((feedback: string) => {
    if (sendAction) {
      sendAction({ action: 'rejectStep', teamId, stepId: rejectModal.stepId, feedback });
    }
    setRejectModal({ visible: false, stepId: '', roleName: '' });
  }, [teamId, rejectModal.stepId, sendAction]);

  const handleRejectCancel = useCallback(() => {
    setRejectModal({ visible: false, stepId: '', roleName: '' });
  }, []);

  if (!team) {
    return (
      <div className="flex items-center justify-center h-full bg-[var(--bg-primary)]">
        <p className="text-xs text-[var(--text-muted)]">No team data for: {teamId}</p>
      </div>
    );
  }

  const roster: RosterEntry[] = team.dag.length > 0
    ? team.dag.map(n => ({ stepId: n.stepId, roleId: n.roleId, instruction: n.instruction }))
    : Object.values(team.steps).map(s => ({ stepId: s.stepId, roleId: s.roleId, instruction: '' }));

  return (
    <div className="flex flex-col h-full bg-[var(--bg-primary)] overflow-hidden">
      <TopBar team={team} />

      {/* Synthesizer card when completed */}
      {team.status === 'completed' && team.finalOutput && (
        <div className="p-3 overflow-y-auto shrink-0 border-b border-[var(--border)]">
          <SynthesizerCard
            finalOutput={team.finalOutput}
            cost={team.cost}
            teamId={teamId}
            completedAt={team.completedAt}
            startedAt={team.startedAt}
            onReplay={onReplay}
          />
        </div>
      )}

      {/* Roster: one row per expert — click opens the full trace in a main-area tab */}
      <div className="flex-1 overflow-y-auto p-2 space-y-1.5">
        {roster.length > 0 ? (
          roster.map((entry) => (
            <RosterRow
              key={entry.stepId}
              entry={entry}
              step={team.steps[entry.stepId]}
              onOpen={() =>
                openExpertStepTab({
                  teamId,
                  stepId: entry.stepId,
                  title: getRoleMeta(entry.roleId).label,
                })
              }
            />
          ))
        ) : (
          <div className="flex items-center justify-center h-full">
            <p className="text-xs text-[var(--text-muted)]">
              {team.status === 'planning' ? 'Planning…' : 'No experts yet'}
            </p>
          </div>
        )}
      </div>

      <RejectFeedbackModal
        roleName={rejectModal.roleName}
        stepId={rejectModal.stepId}
        visible={rejectModal.visible}
        onSubmit={handleRejectSubmit}
        onCancel={handleRejectCancel}
      />
    </div>
  );
}
