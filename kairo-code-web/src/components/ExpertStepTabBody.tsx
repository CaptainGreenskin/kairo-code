import { useExpertTeamStore } from '@store/expertTeamStore';
import { ExpertStepCard } from './ExpertStepCard';

interface ExpertStepTabBodyProps {
    teamId: string;
    stepId: string;
}

/**
 * Main-area tab body for one expert/step's full execution trace. Subscribes live to
 * expertTeamStore so the trace keeps updating while the expert works. Reuses ExpertStepCard
 * (the same component used in the chat card and the roster), here at full editor width.
 */
export function ExpertStepTabBody({ teamId, stepId }: ExpertStepTabBodyProps) {
    const step = useExpertTeamStore((s) => s.teams[teamId]?.steps[stepId]);

    if (!step) {
        return (
            <div className="h-full flex items-center justify-center text-sm text-[var(--text-muted)]">
                Waiting for this expert to start…
            </div>
        );
    }

    return (
        <div className="h-full overflow-y-auto p-4">
            <div className="max-w-[1100px] mx-auto">
                <ExpertStepCard step={step} defaultExpanded />
            </div>
        </div>
    );
}
