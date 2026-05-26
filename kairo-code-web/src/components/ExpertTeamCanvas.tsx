import { Users } from 'lucide-react';
import { useExpertTeamStore } from '@store/expertTeamStore';
import { useSessionStore } from '@store/sessionStore';
import { useBuildPhaseStore } from '@store/buildPhaseStore';
import { ExpertTeamPanel } from './ExpertTeamPanel';

interface ExpertTeamCanvasProps {
    sendAction?: (payload: Record<string, unknown>) => boolean;
}

/**
 * M-Experts-Upgrade / #69: always-on inline Canvas pane for {@code mode === 'experts'}.
 *
 * <p>Thin wrapper around {@link ExpertTeamPanel} — auto-attaches to the team whose
 * {@code teamId} the experts-preset backend stamped onto the most recent {@code PLAN_READY}
 * (see {@link useExpertTeamStore.canvasTeamId}). When no team is active yet (the user
 * hasn't sent a message, or triage demoted to single-agent), renders a minimal empty
 * state so the layout slot doesn't collapse.
 *
 * <p>This is distinct from the modal {@link ExpertTeamPanel} flow used by the
 * command-palette "Browse Teams" entry — that path still uses {@code activeTeamId}.
 */
export function ExpertTeamCanvas({ sendAction }: ExpertTeamCanvasProps) {
    const teamId = useExpertTeamStore((s) => s.canvasTeamId);
    const sessionId = useSessionStore((s) => s.activeSessionId);
    const buildPhase = useBuildPhaseStore((s) => s.phase);

    return (
        <div className="flex flex-col h-full bg-[var(--bg-primary)] border-l border-[var(--border)] min-w-0">
            <div className="flex items-center gap-2 px-3 py-2 border-b border-[var(--border)] bg-[var(--bg-secondary)] shrink-0">
                <Users size={13} className="text-violet-400" />
                <span className="text-xs font-medium text-[var(--text-primary)]">Experts Canvas</span>
                {teamId && (
                    <span className="text-[10px] text-[var(--text-muted)] font-mono truncate">
                        {teamId.substring(0, 12)}…
                    </span>
                )}
            </div>

            {buildPhase === 'PLAN_PENDING' && sessionId && (
                <div className="px-3 py-2 border-b border-[var(--border)] bg-[var(--bg-secondary)] shrink-0">
                    <div className="text-[11px] text-[var(--text-secondary)] mb-1.5">
                        Plan ready — review the DAG below, then approve to start execution.
                    </div>
                    <button
                        onClick={() => {
                            if (!sessionId) return;
                            sendAction?.({ action: 'confirmBuild', sessionId });
                            useBuildPhaseStore.getState().setPhase('EXECUTING');
                        }}
                        className="px-2.5 py-1 text-[11px] font-medium rounded
                            bg-[var(--color-primary)] text-white
                            hover:opacity-90 transition-opacity"
                    >
                        Approve and Run
                    </button>
                </div>
            )}

            <div className="flex-1 overflow-hidden">
                {teamId ? (
                    <ExpertTeamPanel teamId={teamId} sendAction={sendAction} />
                ) : (
                    <CanvasEmptyState />
                )}
            </div>
        </div>
    );
}

function CanvasEmptyState() {
    return (
        <div className="h-full flex flex-col items-center justify-center px-6 text-center">
            <Users size={32} className="text-[var(--text-muted)] opacity-40 mb-3" />
            <div className="text-xs text-[var(--text-secondary)] font-medium mb-1">
                No active expert team yet
            </div>
            <div className="text-[11px] text-[var(--text-muted)] max-w-[240px] leading-relaxed">
                Send a task in the chat. Once the planner produces a DAG, the Canvas will
                show the experts working through it live.
            </div>
        </div>
    );
}
