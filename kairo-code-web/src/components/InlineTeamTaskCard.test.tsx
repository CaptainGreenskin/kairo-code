import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { InlineTeamTaskCard } from './InlineTeamTaskCard';
import { useExpertTeamStore, type TeamState } from '../store/expertTeamStore';

// Mock the expertTeamStore
vi.mock('../store/expertTeamStore', () => ({
  useExpertTeamStore: vi.fn(),
}));

const baseTeam = {
  goal: 'Refactor auth module',
  status: 'plan-ready' as TeamState['status'],
  lastSeq: 1,
  steps: {},
  dag: [] as TeamState['dag'],
  cost: { spent: 0, limit: 10 },
  planPreview: {
    steps: [
      { stepId: 's1', roleId: 'architect', roleName: 'Architect', instruction: 'Design auth flow' },
      { stepId: 's2', roleId: 'coder', roleName: 'Coder', instruction: 'Implement login endpoint' },
    ],
  },
};

describe('InlineTeamTaskCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  function mockTeam(overrides: Partial<typeof baseTeam> = {}) {
    const team = { ...baseTeam, ...overrides };
    (useExpertTeamStore as unknown as ReturnType<typeof vi.fn>).mockImplementation(
      (selector: (s: { teams: Record<string, typeof baseTeam> }) => unknown) =>
        selector({ teams: { 'team-1': team } })
    );
  }

  it('renders goal text', () => {
    mockTeam();
    render(<InlineTeamTaskCard teamId="team-1" />);
    expect(screen.getByText('Refactor auth module')).toBeDefined();
  });

  it('renders phase badge', () => {
    mockTeam();
    render(<InlineTeamTaskCard teamId="team-1" />);
    expect(screen.getByText('plan-ready')).toBeDefined();
  });

  it('shows "Start Team" button only in plan-ready phase', () => {
    mockTeam({ status: 'plan-ready' });
    const sendAction = vi.fn();
    render(<InlineTeamTaskCard teamId="team-1" sendAction={sendAction} />);
    expect(screen.getByText('Start Team')).toBeDefined();
  });

  it('hides "Start Team" during executing', () => {
    mockTeam({ status: 'executing', steps: {}, dag: [{ stepId: 's1', roleId: 'coder', instruction: 'do work', dependsOn: [] }] });
    const sendAction = vi.fn();
    render(<InlineTeamTaskCard teamId="team-1" sendAction={sendAction} />);
    expect(screen.queryByText('Start Team')).toBeNull();
  });

  it('shows Stop button during executing', () => {
    mockTeam({ status: 'executing', steps: {}, dag: [{ stepId: 's1', roleId: 'coder', instruction: 'do work', dependsOn: [] }] });
    const sendAction = vi.fn();
    render(<InlineTeamTaskCard teamId="team-1" sendAction={sendAction} />);
    expect(screen.getByText('Stop')).toBeDefined();
  });

  it('shows step list with status indicators in plan-ready', () => {
    mockTeam();
    render(<InlineTeamTaskCard teamId="team-1" />);
    // Preview steps should render role names
    expect(screen.getByText('Architect')).toBeDefined();
    expect(screen.getByText('Coder')).toBeDefined();
  });

  it('"View DAG" button present when onViewDag provided', () => {
    mockTeam();
    const onViewDag = vi.fn();
    render(<InlineTeamTaskCard teamId="team-1" onViewDag={onViewDag} />);
    expect(screen.getByText('View DAG')).toBeDefined();
  });

  it('"View DAG" button absent when onViewDag not provided', () => {
    mockTeam();
    render(<InlineTeamTaskCard teamId="team-1" />);
    expect(screen.queryByText('View DAG')).toBeNull();
  });

  it('returns null for unknown teamId', () => {
    (useExpertTeamStore as unknown as ReturnType<typeof vi.fn>).mockImplementation(
      (selector: (s: { teams: Record<string, unknown> }) => unknown) =>
        selector({ teams: {} })
    );
    const { container } = render(<InlineTeamTaskCard teamId="unknown" />);
    expect(container.firstChild).toBeNull();
  });

  it('shows planning placeholder during planning phase', () => {
    mockTeam({ status: 'planning' });
    render(<InlineTeamTaskCard teamId="team-1" />);
    expect(screen.getByText('Planning team steps…')).toBeDefined();
  });
});
