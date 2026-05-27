import { useState, useEffect } from 'react';
import { Users, ChevronRight, ChevronLeft } from 'lucide-react';
import { useExpertTeamStore } from '@store/expertTeamStore';
import { useSessionStore } from '@store/sessionStore';
import { useBuildPhaseStore } from '@store/buildPhaseStore';
import { ExpertTeamPanel } from './ExpertTeamPanel';

const COLLAPSED_KEY = 'kairo-experts-canvas-collapsed';

interface ExpertTeamCanvasProps {
    sendAction?: (payload: Record<string, unknown>) => boolean;
}

export function ExpertTeamCanvas({ sendAction }: ExpertTeamCanvasProps) {
    const teamId = useExpertTeamStore((s) => s.canvasTeamId);
    const sessionId = useSessionStore((s) => s.activeSessionId);
    const buildPhase = useBuildPhaseStore((s) => s.phase);

    const [collapsed, setCollapsed] = useState(() => {
        try {
            return localStorage.getItem(COLLAPSED_KEY) === 'true';
        } catch {
            return false;
        }
    });

    useEffect(() => {
        try {
            localStorage.setItem(COLLAPSED_KEY, String(collapsed));
        } catch { /* ignore */ }
    }, [collapsed]);

    // Auto-expand when a team becomes active
    useEffect(() => {
        if (teamId && collapsed) {
            setCollapsed(false);
        }
    }, [teamId]); // eslint-disable-line react-hooks/exhaustive-deps

    if (collapsed) {
        return (
            <div className="flex flex-col h-full bg-[var(--bg-primary)] border-l border-[var(--border)]"
                 style={{ width: 36 }}>
                <button
                    onClick={() => setCollapsed(false)}
                    className="flex flex-col items-center gap-1 py-3 px-1 hover:bg-[var(--bg-tertiary)] transition-colors"
                    title="Expand Canvas"
                >
                    <ChevronLeft size={12} className="text-[var(--text-muted)]" />
                    <Users size={14} className="text-violet-400" />
                    <span className="text-[9px] text-[var(--text-muted)] font-medium"
                          style={{ writingMode: 'vertical-rl', textOrientation: 'mixed' }}>
                        Experts
                    </span>
                </button>
            </div>
        );
    }

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
                <button
                    onClick={() => setCollapsed(true)}
                    className="ml-auto p-0.5 rounded hover:bg-[var(--bg-tertiary)] transition-colors"
                    title="Collapse Canvas"
                >
                    <ChevronRight size={13} className="text-[var(--text-muted)]" />
                </button>
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
