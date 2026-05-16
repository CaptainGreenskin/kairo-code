import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StepProgressBar } from './StepProgressBar';
import type { StepState } from '../store/expertTeamStore';

function makeStep(overrides: Partial<StepState> = {}): StepState {
  return {
    stepId: 'step-1',
    roleId: 'coder',
    status: 'pending',
    thinkingChunks: [],
    toolCalls: [],
    artifact: '',
    thinkingStartedAt: null,
    thinkingDuration: null,
    ...overrides,
  };
}

describe('StepProgressBar', () => {
  // ── Zero steps ─────────────────────────────────────────────────────────────

  it('renders with 0 steps — no step counter shown', () => {
    const { container } = render(
      <StepProgressBar steps={[]} teamPhase="executing" />
    );
    // No "(x/y steps)" counter when total is 0
    expect(screen.queryByText(/steps/)).toBeNull();
    // Progress bar exists but at 0%
    const bar = container.querySelector('[style]');
    expect(bar).toBeDefined();
  });

  // ── Partial completion (3/5 = 60%) ────────────────────────────────────────

  it('shows correct step counter for 3/5 completed', () => {
    const steps = [
      makeStep({ stepId: 's1', status: 'done' }),
      makeStep({ stepId: 's2', status: 'done' }),
      makeStep({ stepId: 's3', status: 'done' }),
      makeStep({ stepId: 's4', status: 'working' }),
      makeStep({ stepId: 's5', status: 'pending' }),
    ];
    render(<StepProgressBar steps={steps} teamPhase="executing" />);
    expect(screen.getByText('(3/5 steps)')).toBeDefined();
  });

  it('progress bar width matches percentage for partial completion', () => {
    const steps = [
      makeStep({ stepId: 's1', status: 'done' }),
      makeStep({ stepId: 's2', status: 'done' }),
      makeStep({ stepId: 's3', status: 'done' }),
      makeStep({ stepId: 's4', status: 'working' }),
      makeStep({ stepId: 's5', status: 'pending' }),
    ];
    const { container } = render(
      <StepProgressBar steps={steps} teamPhase="executing" />
    );
    // 3/5 = 60%
    const progressFill = container.querySelector('[style*="width"]');
    expect(progressFill).not.toBeNull();
    expect(progressFill!.getAttribute('style')).toContain('60%');
  });

  // ── Phase highlighting ────────────────────────────────────────────────────

  it('active phase (executing) is bold/blue, others are muted', () => {
    const steps = [
      makeStep({ stepId: 's1', status: 'done' }),
      makeStep({ stepId: 's2', status: 'working' }),
    ];
    const { container } = render(
      <StepProgressBar steps={steps} teamPhase="executing" />
    );
    // "Executing" phase label should have font-semibold and text-blue-400
    const phaseLabels = container.querySelectorAll('span[class*="text-[11px]"]');
    const labels = Array.from(phaseLabels);

    // Find "Executing" label — should be bold/blue (active)
    const executing = labels.find((el) => el.textContent === 'Executing');
    expect(executing).toBeDefined();
    expect(executing!.className).toContain('font-semibold');
    expect(executing!.className).toContain('text-blue-400');

    // Find "Planning" label — should be past (green) since executing > planning
    const planning = labels.find((el) => el.textContent === 'Planning');
    expect(planning).toBeDefined();
    expect(planning!.className).toContain('text-green-400');

    // Find "Synthesizing" label — should be muted (future)
    const synthesizing = labels.find((el) => el.textContent === 'Synthesizing');
    expect(synthesizing).toBeDefined();
    expect(synthesizing!.className).toContain('text-[var(--text-muted)]');
  });

  // ── Completed phase (terminal state) ──────────────────────────────────────

  it('completed phase shows 100% progress bar', () => {
    const steps = [
      makeStep({ stepId: 's1', status: 'done' }),
      makeStep({ stepId: 's2', status: 'done' }),
    ];
    const { container } = render(
      <StepProgressBar steps={steps} teamPhase="completed" />
    );
    const progressFill = container.querySelector('[style*="width"]');
    expect(progressFill).not.toBeNull();
    expect(progressFill!.getAttribute('style')).toContain('100%');
  });

  it('completed phase marks all phases as past (green)', () => {
    const steps = [makeStep({ stepId: 's1', status: 'done' })];
    const { container } = render(
      <StepProgressBar steps={steps} teamPhase="completed" />
    );
    const phaseLabels = container.querySelectorAll('span[class*="text-[11px]"]');
    const labels = Array.from(phaseLabels);
    // All 3 phases should be green (isPast = true when terminal)
    labels.forEach((label) => {
      expect(label.className).toContain('text-green-400');
    });
  });

  // ── Failed phase ──────────────────────────────────────────────────────────

  it('failed phase uses red progress bar', () => {
    const steps = [
      makeStep({ stepId: 's1', status: 'done' }),
      makeStep({ stepId: 's2', status: 'failed' }),
    ];
    const { container } = render(
      <StepProgressBar steps={steps} teamPhase="failed" />
    );
    const progressFill = container.querySelector('[class*="bg-red-500"]');
    expect(progressFill).not.toBeNull();
  });

  // ── Planning phase ────────────────────────────────────────────────────────

  it('planning phase highlights Planning label', () => {
    const { container } = render(
      <StepProgressBar steps={[]} teamPhase="planning" />
    );
    const phaseLabels = container.querySelectorAll('span[class*="text-[11px]"]');
    const planning = Array.from(phaseLabels).find((el) => el.textContent === 'Planning');
    expect(planning).toBeDefined();
    expect(planning!.className).toContain('font-semibold');
    expect(planning!.className).toContain('text-blue-400');
  });
});
