import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ExpertStepCard } from './ExpertStepCard';
import type { StepState, ToolCallEntry } from '../store/expertTeamStore';

// Mock useThinkingTimer to avoid real timers in tests
vi.mock('../hooks/useThinkingTimer', () => ({
  useThinkingTimer: vi.fn(() => ''),
}));

import { useThinkingTimer } from '../hooks/useThinkingTimer';

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

function makeToolCall(overrides: Partial<ToolCallEntry> = {}): ToolCallEntry {
  return {
    toolName: 'read_file',
    args: { file_path: '/src/main.ts' },
    timestamp: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

describe('ExpertStepCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    (useThinkingTimer as ReturnType<typeof vi.fn>).mockReturnValue('');
  });

  // ── Pending state ─────────────────────────────────────────────────────────

  describe('pending state', () => {
    it('renders with gray "Pending" badge', () => {
      render(<ExpertStepCard step={makeStep({ status: 'pending' })} />);
      expect(screen.getByText('Pending')).toBeDefined();
    });

    it('does not expand on click (no timeline content)', () => {
      render(<ExpertStepCard step={makeStep({ status: 'pending' })} />);
      // The header button should not toggle — cursor-default indicates non-interactive
      const button = screen.getByRole('button');
      fireEvent.click(button);
      // No timeline entries should appear since pending has no content
      expect(screen.queryByText('Completed')).toBeNull();
      expect(screen.queryByText('Failed')).toBeNull();
    });
  });

  // ── Thinking state ────────────────────────────────────────────────────────

  describe('thinking state', () => {
    it('renders blue "Thinking" badge', () => {
      render(
        <ExpertStepCard
          step={makeStep({
            status: 'thinking',
            thinkingStartedAt: Date.now() - 5000,
            thinkingChunks: ['Analyzing the code...'],
          })}
        />
      );
      expect(screen.getByText('Thinking')).toBeDefined();
    });

    it('shows live timer text from useThinkingTimer', () => {
      (useThinkingTimer as ReturnType<typeof vi.fn>).mockReturnValue('8s');
      render(
        <ExpertStepCard
          step={makeStep({
            status: 'thinking',
            thinkingStartedAt: Date.now() - 8000,
            thinkingChunks: ['Pondering...'],
          })}
          defaultExpanded={true}
        />
      );
      // Should show thinking duration in timeline
      expect(screen.getByText(/Thinking.*8s/)).toBeDefined();
    });
  });

  // ── Working state ─────────────────────────────────────────────────────────

  describe('working state', () => {
    it('renders "Working" badge', () => {
      render(
        <ExpertStepCard
          step={makeStep({
            status: 'working',
            toolCalls: [makeToolCall()],
          })}
        />
      );
      expect(screen.getByText('Working')).toBeDefined();
    });

    it('shows tool call entries when expanded', () => {
      render(
        <ExpertStepCard
          step={makeStep({
            status: 'working',
            toolCalls: [
              makeToolCall({ toolName: 'read_file', args: { file_path: '/src/auth.ts' } }),
              makeToolCall({ toolName: 'bash', args: { command: 'npm test' } }),
            ],
          })}
          defaultExpanded={true}
        />
      );
      // Expanded view renders a ToolCallCard per tool call (reusing main-chat rendering),
      // which surfaces the raw tool name.
      expect(screen.getByText('read_file')).toBeDefined();
      expect(screen.getByText('bash')).toBeDefined();
    });
  });

  // ── Done state ────────────────────────────────────────────────────────────

  describe('done state', () => {
    it('renders green "✓ Done" badge', () => {
      render(
        <ExpertStepCard
          step={makeStep({
            status: 'done',
            thinkingDuration: 5000,
            toolCalls: [makeToolCall()],
          })}
        />
      );
      expect(screen.getByText('✓ Done')).toBeDefined();
    });

    it('shows green checkmark Completed entry when expanded', () => {
      render(
        <ExpertStepCard
          step={makeStep({
            status: 'done',
            thinkingChunks: ['done thinking'],
            toolCalls: [makeToolCall()],
          })}
          defaultExpanded={true}
        />
      );
      expect(screen.getByText('Completed')).toBeDefined();
    });
  });

  // ── Failed state ──────────────────────────────────────────────────────────

  describe('failed state', () => {
    it('renders red "✗ Failed" badge', () => {
      render(
        <ExpertStepCard
          step={makeStep({
            status: 'failed',
            toolCalls: [makeToolCall()],
          })}
        />
      );
      expect(screen.getByText('✗ Failed')).toBeDefined();
    });

    it('shows red "Failed" entry when expanded', () => {
      render(
        <ExpertStepCard
          step={makeStep({
            status: 'failed',
            thinkingChunks: ['trying...'],
            toolCalls: [makeToolCall()],
          })}
          defaultExpanded={true}
        />
      );
      expect(screen.getByText('Failed')).toBeDefined();
    });
  });

  // ── Expand/Collapse toggle ────────────────────────────────────────────────

  describe('expand/collapse toggle', () => {
    it('starts collapsed by default and expands on click', () => {
      render(
        <ExpertStepCard
          step={makeStep({
            status: 'done',
            thinkingChunks: ['I thought...'],
            toolCalls: [makeToolCall()],
          })}
        />
      );
      // Collapsed: should not see timeline entries
      expect(screen.queryByText('Completed')).toBeNull();

      // Click header to expand
      fireEvent.click(screen.getByRole('button'));
      expect(screen.getByText('Completed')).toBeDefined();

      // Click again to collapse
      fireEvent.click(screen.getByRole('button'));
      expect(screen.queryByText('Completed')).toBeNull();
    });

    it('starts expanded when defaultExpanded is true', () => {
      render(
        <ExpertStepCard
          step={makeStep({
            status: 'done',
            thinkingChunks: ['thinking...'],
            toolCalls: [makeToolCall()],
          })}
          defaultExpanded={true}
        />
      );
      expect(screen.getByText('Completed')).toBeDefined();
    });
  });

  // ── Tool summary derivation ───────────────────────────────────────────────

  describe('tool summary in collapsed view', () => {
    it('shows correct tool counts in summary line', () => {
      render(
        <ExpertStepCard
          step={makeStep({
            status: 'done',
            thinkingDuration: 3000,
            toolCalls: [
              makeToolCall({ toolName: 'read_file', args: { file_path: '/a.ts' } }),
              makeToolCall({ toolName: 'read_file', args: { file_path: '/b.ts' } }),
              makeToolCall({ toolName: 'edit_file', args: { file_path: '/c.ts' } }),
              makeToolCall({ toolName: 'bash', args: { command: 'npm test' } }),
              makeToolCall({ toolName: 'grep', args: { query: 'foo' } }),
            ],
          })}
        />
      );
      // Collapsed summary should include tool counts
      // "Thought · 3s | Read 2 · Write 1 · Cmd 1 · Search 1"
      expect(screen.getByText(/Read 2/)).toBeDefined();
      expect(screen.getByText(/Write 1/)).toBeDefined();
      expect(screen.getByText(/Cmd 1/)).toBeDefined();
      expect(screen.getByText(/Search 1/)).toBeDefined();
    });
  });

  // ── Role metadata ─────────────────────────────────────────────────────────

  describe('role metadata', () => {
    it('renders correct role label for known role', () => {
      render(<ExpertStepCard step={makeStep({ roleId: 'architect' })} />);
      expect(screen.getByText('Architect')).toBeDefined();
    });

    it('falls back to roleId for unknown role', () => {
      render(<ExpertStepCard step={makeStep({ roleId: 'CustomRole' })} />);
      expect(screen.getByText('CustomRole')).toBeDefined();
    });
  });
});
