import { useState, useCallback, useEffect } from 'react';

export interface PlanStep {
    text: string;
    done: boolean;
}

/**
 * Manages plan step state driven by agent events and message content.
 * Falls back to parsing plan steps from agent message text if no PLAN_STEPS event.
 */
export function usePlanSteps(agentMessages: Array<{ role: string; content: string }>) {
    const [steps, setSteps] = useState<PlanStep[]>([]);
    const [currentStepIndex, setCurrentStepIndex] = useState(-1);

    const setPlanSteps = useCallback((newSteps: string[]) => {
        setSteps(newSteps.map(text => ({ text, done: false })));
        setCurrentStepIndex(-1);
    }, []);

    const markStepDone = useCallback((index: number) => {
        setSteps(prev => prev.map((s, i) => i === index ? { ...s, done: true } : s));
        setCurrentStepIndex(index + 1);
    }, []);

    const clearPlan = useCallback(() => {
        setSteps([]);
        setCurrentStepIndex(-1);
    }, []);

    // Parse plan from latest assistant message if steps not yet set
    useEffect(() => {
        if (steps.length > 0) return;
        const lastAssistant = [...agentMessages].reverse().find(m => m.role === 'assistant');
        if (!lastAssistant) return;
        const parsed = parseStepsFromText(lastAssistant.content);
        if (parsed.length >= 2) {
            setSteps(parsed.map(text => ({ text, done: false })));
        }
    }, [agentMessages, steps.length]);

    return { steps, setPlanSteps, markStepDone, clearPlan, currentStepIndex };
}

function parseStepsFromText(text: string): string[] {
    if (!text) return [];
    // Match "- [ ] ..." or "1. ..."
    const checkboxes = [...text.matchAll(/^\s*-\s*\[[ x]\]\s*(.+)/gm)].map(m => m[1].trim());
    if (checkboxes.length >= 2) return checkboxes.slice(0, 20);
    const numbered = [...text.matchAll(/^\s*\d+\.\s+(.+)/gm)].map(m => m[1].trim());
    if (numbered.length >= 2) return numbered.slice(0, 20);
    return [];
}
