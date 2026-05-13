import { describe, it, expect, beforeEach } from 'vitest';
import { useExpertTeamStore, type TeamWsEvent } from './expertTeamStore';

function makeEvent(overrides: Partial<TeamWsEvent> & { teamId: string; eventType: string; seq: number }): TeamWsEvent {
  return {
    type: 'TEAM_EVENT',
    attributes: {},
    timestamp: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

describe('expertTeamStore', () => {
  beforeEach(() => {
    useExpertTeamStore.getState().reset();
  });

  // ── Seq-based dedup ──────────────────────────────────────────────────────

  describe('seq-based dedup', () => {
    it('processes event with seq > lastSeq', () => {
      const event = makeEvent({
        teamId: 'team-1', eventType: 'TEAM_STARTED', seq: 1,
        attributes: { goal: 'test goal' },
      });
      useExpertTeamStore.getState().handleTeamEvent(event);
      const team = useExpertTeamStore.getState().teams['team-1'];
      expect(team).toBeDefined();
      expect(team.lastSeq).toBe(1);
      expect(team.goal).toBe('test goal');
    });

    it('ignores event with seq <= lastSeq (duplicate)', () => {
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'TEAM_STARTED', seq: 5,
        attributes: { goal: 'initial' },
      }));
      // Send a stale event — should be ignored
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'TEAM_FAILED', seq: 3,
        attributes: {},
      }));
      const team = useExpertTeamStore.getState().teams['team-1'];
      expect(team.status).toBe('planning');
      expect(team.lastSeq).toBe(5);
    });

    it('ignores event with seq === lastSeq (exact duplicate)', () => {
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'TEAM_STARTED', seq: 3, attributes: {},
      }));
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'TEAM_COMPLETED', seq: 3, attributes: {},
      }));
      expect(useExpertTeamStore.getState().teams['team-1'].status).toBe('planning');
    });
  });

  // ── Event transitions ────────────────────────────────────────────────────

  describe('event transitions', () => {
    it('TEAM_STARTED → creates team with planning status', () => {
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'TEAM_STARTED', seq: 1,
        attributes: { goal: 'Build something' },
      }));
      const team = useExpertTeamStore.getState().teams['team-1'];
      expect(team.status).toBe('planning');
      expect(team.goal).toBe('Build something');
      expect(team.startedAt).toBe('2026-01-01T00:00:00Z');
    });

    it('STEP_ASSIGNED → creates step with assigned status, team goes executing', () => {
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'TEAM_STARTED', seq: 1, attributes: {},
      }));
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'STEP_ASSIGNED', seq: 2,
        stepId: 'step-1',
        attributes: { roleId: 'researcher' },
      }));
      const team = useExpertTeamStore.getState().teams['team-1'];
      expect(team.status).toBe('executing');
      expect(team.steps['step-1']).toBeDefined();
      expect(team.steps['step-1'].status).toBe('assigned');
      expect(team.steps['step-1'].roleId).toBe('researcher');
    });

    it('STEP_THINKING → appends to thinkingChunks', () => {
      // Setup: start team + assign step
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'STEP_ASSIGNED', seq: 1,
        stepId: 'step-1', attributes: { roleId: 'coder' },
      }));
      // First thinking chunk
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'STEP_THINKING', seq: 2,
        stepId: 'step-1', attributes: { text: 'Thinking about' },
      }));
      // Second thinking chunk
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'STEP_THINKING', seq: 3,
        stepId: 'step-1', attributes: { text: ' the problem' },
      }));
      const step = useExpertTeamStore.getState().teams['team-1'].steps['step-1'];
      expect(step.status).toBe('thinking');
      expect(step.thinkingChunks).toEqual(['Thinking about', ' the problem']);
    });

    it('STEP_TOOL_CALL → appends to toolCalls', () => {
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'STEP_ASSIGNED', seq: 1,
        stepId: 'step-1', attributes: { roleId: 'coder' },
      }));
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'STEP_TOOL_CALL', seq: 2,
        stepId: 'step-1',
        attributes: { toolName: 'readFile', args: { path: '/a.ts' }, result: 'content' },
      }));
      const step = useExpertTeamStore.getState().teams['team-1'].steps['step-1'];
      expect(step.status).toBe('working');
      expect(step.toolCalls).toHaveLength(1);
      expect(step.toolCalls[0].toolName).toBe('readFile');
      expect(step.toolCalls[0].args).toEqual({ path: '/a.ts' });
      expect(step.toolCalls[0].result).toBe('content');
    });

    it('STEP_ARTIFACT_CHUNK → appends to artifact', () => {
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'STEP_ASSIGNED', seq: 1,
        stepId: 'step-1', attributes: { roleId: 'coder' },
      }));
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'STEP_ARTIFACT_CHUNK', seq: 2,
        stepId: 'step-1', attributes: { chunk: 'Hello ' },
      }));
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'STEP_ARTIFACT_CHUNK', seq: 3,
        stepId: 'step-1', attributes: { chunk: 'World' },
      }));
      const step = useExpertTeamStore.getState().teams['team-1'].steps['step-1'];
      expect(step.artifact).toBe('Hello World');
    });

    it('STEP_COMPLETED → marks step done with output', () => {
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'STEP_ASSIGNED', seq: 1,
        stepId: 'step-1', attributes: { roleId: 'coder' },
      }));
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'STEP_COMPLETED', seq: 2,
        stepId: 'step-1', attributes: { output: 'Final result' },
      }));
      const step = useExpertTeamStore.getState().teams['team-1'].steps['step-1'];
      expect(step.status).toBe('done');
      expect(step.artifact).toBe('Final result');
    });

    it('EVALUATION_RESULT → sets evaluation on step', () => {
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'STEP_ASSIGNED', seq: 1,
        stepId: 'step-1', attributes: { roleId: 'coder' },
      }));
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'EVALUATION_RESULT', seq: 2,
        stepId: 'step-1',
        attributes: { verdict: 'PASS', feedback: 'Good job', round: 1, maxRounds: 3 },
      }));
      const step = useExpertTeamStore.getState().teams['team-1'].steps['step-1'];
      expect(step.evaluation).toEqual({
        verdict: 'PASS', feedback: 'Good job', round: 1, maxRounds: 3,
      });
    });

    it('TEAM_COMPLETED → sets completed status + finalOutput', () => {
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'TEAM_STARTED', seq: 1, attributes: {},
      }));
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'TEAM_COMPLETED', seq: 2,
        attributes: { finalOutput: 'All done!' },
        timestamp: '2026-01-01T01:00:00Z',
      }));
      const team = useExpertTeamStore.getState().teams['team-1'];
      expect(team.status).toBe('completed');
      expect(team.finalOutput).toBe('All done!');
      expect(team.completedAt).toBe('2026-01-01T01:00:00Z');
    });

    it('TEAM_FAILED → sets failed status', () => {
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'TEAM_STARTED', seq: 1, attributes: {},
      }));
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'TEAM_FAILED', seq: 2,
        attributes: {},
        timestamp: '2026-01-01T01:00:00Z',
      }));
      const team = useExpertTeamStore.getState().teams['team-1'];
      expect(team.status).toBe('failed');
      expect(team.completedAt).toBe('2026-01-01T01:00:00Z');
    });

    it('TEAM_TIMEOUT → sets timeout status', () => {
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'TEAM_STARTED', seq: 1, attributes: {},
      }));
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'TEAM_TIMEOUT', seq: 2, attributes: {},
      }));
      expect(useExpertTeamStore.getState().teams['team-1'].status).toBe('timeout');
    });

    it('HANDOFF → marks step as failed', () => {
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'STEP_ASSIGNED', seq: 1,
        stepId: 'step-1', attributes: { roleId: 'coder' },
      }));
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'HANDOFF', seq: 2,
        stepId: 'step-1', attributes: {},
      }));
      expect(useExpertTeamStore.getState().teams['team-1'].steps['step-1'].status).toBe('failed');
    });

    it('updates cost when present in attributes', () => {
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'TEAM_STARTED', seq: 1,
        attributes: { cost: { spent: 10, budget: 100 } },
      }));
      expect(useExpertTeamStore.getState().teams['team-1'].cost).toEqual({ spent: 10, budget: 100 });
    });
  });

  // ── handleBatchEvents ────────────────────────────────────────────────────

  describe('handleBatchEvents', () => {
    it('applies multiple thinking chunks in single update', () => {
      // Setup: assign a step first
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'STEP_ASSIGNED', seq: 1,
        stepId: 'step-1', attributes: { roleId: 'coder' },
      }));

      useExpertTeamStore.getState().handleBatchEvents([
        makeEvent({ teamId: 'team-1', eventType: 'STEP_THINKING', seq: 2, stepId: 'step-1', attributes: { text: 'chunk1' } }),
        makeEvent({ teamId: 'team-1', eventType: 'STEP_THINKING', seq: 3, stepId: 'step-1', attributes: { text: 'chunk2' } }),
        makeEvent({ teamId: 'team-1', eventType: 'STEP_THINKING', seq: 4, stepId: 'step-1', attributes: { text: 'chunk3' } }),
      ]);

      const step = useExpertTeamStore.getState().teams['team-1'].steps['step-1'];
      expect(step.thinkingChunks).toEqual(['chunk1', 'chunk2', 'chunk3']);
      expect(step.status).toBe('thinking');
    });

    it('applies artifact chunks in single update', () => {
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'STEP_ASSIGNED', seq: 1,
        stepId: 'step-1', attributes: { roleId: 'coder' },
      }));

      useExpertTeamStore.getState().handleBatchEvents([
        makeEvent({ teamId: 'team-1', eventType: 'STEP_ARTIFACT_CHUNK', seq: 2, stepId: 'step-1', attributes: { chunk: 'Hello ' } }),
        makeEvent({ teamId: 'team-1', eventType: 'STEP_ARTIFACT_CHUNK', seq: 3, stepId: 'step-1', attributes: { chunk: 'World' } }),
      ]);

      expect(useExpertTeamStore.getState().teams['team-1'].steps['step-1'].artifact).toBe('Hello World');
    });

    it('deduplicates within batch based on seq', () => {
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'STEP_ASSIGNED', seq: 1,
        stepId: 'step-1', attributes: { roleId: 'coder' },
      }));

      useExpertTeamStore.getState().handleBatchEvents([
        makeEvent({ teamId: 'team-1', eventType: 'STEP_THINKING', seq: 2, stepId: 'step-1', attributes: { text: 'first' } }),
        makeEvent({ teamId: 'team-1', eventType: 'STEP_THINKING', seq: 2, stepId: 'step-1', attributes: { text: 'duplicate' } }),
        makeEvent({ teamId: 'team-1', eventType: 'STEP_THINKING', seq: 3, stepId: 'step-1', attributes: { text: 'second' } }),
      ]);

      const step = useExpertTeamStore.getState().teams['team-1'].steps['step-1'];
      expect(step.thinkingChunks).toEqual(['first', 'second']);
    });

    it('handles empty events array gracefully', () => {
      useExpertTeamStore.getState().handleBatchEvents([]);
      expect(useExpertTeamStore.getState().teams).toEqual({});
    });

    it('handles events for multiple teams', () => {
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'STEP_ASSIGNED', seq: 1,
        stepId: 'step-1', attributes: { roleId: 'coder' },
      }));
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-2', eventType: 'STEP_ASSIGNED', seq: 1,
        stepId: 'step-A', attributes: { roleId: 'reviewer' },
      }));

      useExpertTeamStore.getState().handleBatchEvents([
        makeEvent({ teamId: 'team-1', eventType: 'STEP_THINKING', seq: 2, stepId: 'step-1', attributes: { text: 'team1-thought' } }),
        makeEvent({ teamId: 'team-2', eventType: 'STEP_THINKING', seq: 2, stepId: 'step-A', attributes: { text: 'team2-thought' } }),
      ]);

      expect(useExpertTeamStore.getState().teams['team-1'].steps['step-1'].thinkingChunks).toEqual(['team1-thought']);
      expect(useExpertTeamStore.getState().teams['team-2'].steps['step-A'].thinkingChunks).toEqual(['team2-thought']);
    });
  });

  // ── setActiveTeam / getActiveTeam ────────────────────────────────────────

  describe('setActiveTeam / getActiveTeam', () => {
    it('returns null when no active team', () => {
      expect(useExpertTeamStore.getState().getActiveTeam()).toBeNull();
    });

    it('returns team when active team is set', () => {
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'TEAM_STARTED', seq: 1,
        attributes: { goal: 'Test' },
      }));
      useExpertTeamStore.getState().setActiveTeam('team-1');
      const active = useExpertTeamStore.getState().getActiveTeam();
      expect(active).not.toBeNull();
      expect(active!.teamId).toBe('team-1');
    });

    it('returns null when active team does not exist in teams map', () => {
      useExpertTeamStore.getState().setActiveTeam('nonexistent');
      expect(useExpertTeamStore.getState().getActiveTeam()).toBeNull();
    });
  });

  // ── reset ────────────────────────────────────────────────────────────────

  describe('reset', () => {
    it('clears all teams and activeTeamId', () => {
      useExpertTeamStore.getState().handleTeamEvent(makeEvent({
        teamId: 'team-1', eventType: 'TEAM_STARTED', seq: 1, attributes: {},
      }));
      useExpertTeamStore.getState().setActiveTeam('team-1');

      useExpertTeamStore.getState().reset();

      expect(useExpertTeamStore.getState().teams).toEqual({});
      expect(useExpertTeamStore.getState().activeTeamId).toBeNull();
    });
  });
});
