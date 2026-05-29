import { ExternalLink } from 'lucide-react';
import { useExpertTeamStore } from '@store/expertTeamStore';
import { useOpenFilesStore } from '@store/openFilesStore';
import { ExpertStepCard } from './ExpertStepCard';

const ROLE_LABELS: Record<string, string> = {
    architect: 'Architect', researcher: 'Researcher', coder: 'Coder',
    reviewer: 'Reviewer', tester: 'Tester', synthesizer: 'Synthesizer',
};

interface ExpertStepChatCardProps {
    teamId: string;
    stepId: string;
}

/**
 * Inline chat card for one expert/step (qoder-style): a collapsible ExpertStepCard bound live
 * to expertTeamStore, plus an "open in tab" affordance for the full-width view. Reuses the same
 * ExpertStepCard rendered in the roster tab and the docked panel.
 */
export function ExpertStepChatCard({ teamId, stepId }: ExpertStepChatCardProps) {
    const step = useExpertTeamStore((s) => s.teams[teamId]?.steps[stepId]);
    const openExpertStepTab = useOpenFilesStore((s) => s.openExpertStepTab);

    if (!step) {
        return (
            <div className="mb-4 text-xs text-[var(--text-muted)] italic">
                Expert starting…
            </div>
        );
    }

    const title = ROLE_LABELS[step.roleId.toLowerCase()] ?? step.roleId;

    return (
        <div className="mb-4 w-full min-w-0 group/expertcard relative">
            <ExpertStepCard step={step} defaultExpanded={false} />
            <button
                onClick={() => openExpertStepTab({ teamId, stepId, title })}
                className="absolute top-2 right-2 p-1 rounded opacity-0 group-hover/expertcard:opacity-100
                           hover:bg-[var(--bg-tertiary)] transition-opacity"
                title="Open full execution in a tab"
            >
                <ExternalLink size={13} className="text-[var(--text-muted)]" />
            </button>
        </div>
    );
}
