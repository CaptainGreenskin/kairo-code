import { describe, it, expect, vi, beforeEach, afterEach, type Mock } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useExpertTeamHandler, type UseExpertTeamHandlerOptions } from '../useExpertTeamHandler';
import { useExpertTeamStore } from '../../store/expertTeamStore';

describe('useExpertTeamHandler', () => {
  let sendAction: Mock<(payload: Record<string, unknown>) => boolean>;

  beforeEach(() => {
    useExpertTeamStore.getState().reset();
    sendAction = vi.fn<(payload: Record<string, unknown>) => boolean>().mockReturnValue(true);
    // Mock requestAnimationFrame
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
      const id = setTimeout(() => cb(Date.now()), 0);
      return id as unknown as number;
    });
    vi.stubGlobal('cancelAnimationFrame', (id: number) => clearTimeout(id));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  describe('subscribe', () => {
    it('sends subscribeTeam action with teamId and lastSeq', () => {
      const { result } = renderHook(() =>
        useExpertTeamHandler({ sendAction, isConnected: true }),
      );

      act(() => {
        result.current.subscribe('team-1');
      });

      expect(sendAction).toHaveBeenCalledWith({
        action: 'subscribeTeam',
        teamId: 'team-1',
        lastSeq: 0,
      });
      expect(result.current.isSubscribed).toBe(true);
    });

    it('sends unsubscribeTeam on team switch before subscribing new team', () => {
      const { result } = renderHook(() =>
        useExpertTeamHandler({ sendAction, isConnected: true }),
      );

      act(() => {
        result.current.subscribe('team-1');
      });
      act(() => {
        result.current.subscribe('team-2');
      });

      expect(sendAction).toHaveBeenCalledWith({
        action: 'unsubscribeTeam',
        teamId: 'team-1',
      });
      expect(sendAction).toHaveBeenCalledWith({
        action: 'subscribeTeam',
        teamId: 'team-2',
        lastSeq: 0,
      });
    });
  });

  describe('unsubscribe', () => {
    it('sends unsubscribeTeam action', () => {
      const { result } = renderHook(() =>
        useExpertTeamHandler({ sendAction, isConnected: true }),
      );

      act(() => {
        result.current.subscribe('team-1');
      });
      act(() => {
        result.current.unsubscribe();
      });

      expect(sendAction).toHaveBeenCalledWith({
        action: 'unsubscribeTeam',
        teamId: 'team-1',
      });
      expect(result.current.isSubscribed).toBe(false);
    });

    it('does nothing when not subscribed', () => {
      const { result } = renderHook(() =>
        useExpertTeamHandler({ sendAction, isConnected: true }),
      );

      act(() => {
        result.current.unsubscribe();
      });

      // Only no calls (sendAction not invoked for unsubscribe)
      expect(sendAction).not.toHaveBeenCalledWith(
        expect.objectContaining({ action: 'unsubscribeTeam' }),
      );
    });
  });

  describe('handleRawMessage', () => {
    it('filters non-TEAM_EVENT messages', () => {
      const { result } = renderHook(() =>
        useExpertTeamHandler({ sendAction, isConnected: true }),
      );

      act(() => {
        result.current.subscribe('team-1');
      });
      act(() => {
        result.current.handleRawMessage(JSON.stringify({ type: 'OTHER', data: 'foo' }));
      });

      // Store should have no teams since no TEAM_EVENT was processed
      expect(useExpertTeamStore.getState().teams['team-1']).toBeUndefined();
    });

    it('filters events for non-subscribed teams', () => {
      const { result } = renderHook(() =>
        useExpertTeamHandler({ sendAction, isConnected: true }),
      );

      act(() => {
        result.current.subscribe('team-1');
      });
      act(() => {
        result.current.handleRawMessage(JSON.stringify({
          type: 'TEAM_EVENT', teamId: 'team-other', eventType: 'TEAM_STARTED',
          seq: 1, attributes: { goal: 'wrong team' }, timestamp: '2026-01-01T00:00:00Z',
        }));
      });

      expect(useExpertTeamStore.getState().teams['team-other']).toBeUndefined();
    });

    it('dispatches lifecycle events immediately to store', () => {
      const { result } = renderHook(() =>
        useExpertTeamHandler({ sendAction, isConnected: true }),
      );

      act(() => {
        result.current.subscribe('team-1');
      });
      act(() => {
        result.current.handleRawMessage(JSON.stringify({
          type: 'TEAM_EVENT', teamId: 'team-1', eventType: 'TEAM_STARTED',
          seq: 1, attributes: { goal: 'Immediate dispatch' }, timestamp: '2026-01-01T00:00:00Z',
        }));
      });

      // Lifecycle events are dispatched immediately (not buffered)
      const team = useExpertTeamStore.getState().teams['team-1'];
      expect(team).toBeDefined();
      expect(team.goal).toBe('Immediate dispatch');
    });

    it('buffers STEP_THINKING events for rAF flush', async () => {
      const { result } = renderHook(() =>
        useExpertTeamHandler({ sendAction, isConnected: true }),
      );

      // Setup: first create a step via lifecycle event
      act(() => {
        result.current.subscribe('team-1');
      });
      act(() => {
        result.current.handleRawMessage(JSON.stringify({
          type: 'TEAM_EVENT', teamId: 'team-1', eventType: 'STEP_ASSIGNED',
          seq: 1, stepId: 'step-1', attributes: { roleId: 'coder' }, timestamp: '2026-01-01T00:00:00Z',
        }));
      });

      // Send thinking events — these should be buffered
      act(() => {
        result.current.handleRawMessage(JSON.stringify({
          type: 'TEAM_EVENT', teamId: 'team-1', eventType: 'STEP_THINKING',
          seq: 2, stepId: 'step-1', attributes: { text: 'thought-1' }, timestamp: '2026-01-01T00:00:00Z',
        }));
        result.current.handleRawMessage(JSON.stringify({
          type: 'TEAM_EVENT', teamId: 'team-1', eventType: 'STEP_THINKING',
          seq: 3, stepId: 'step-1', attributes: { text: 'thought-2' }, timestamp: '2026-01-01T00:00:00Z',
        }));
      });

      // Wait for rAF to flush
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 50));
      });

      const step = useExpertTeamStore.getState().teams['team-1'].steps['step-1'];
      expect(step.thinkingChunks).toContain('thought-1');
      expect(step.thinkingChunks).toContain('thought-2');
    });

    it('ignores malformed JSON gracefully', () => {
      const { result } = renderHook(() =>
        useExpertTeamHandler({ sendAction, isConnected: true }),
      );

      act(() => {
        result.current.subscribe('team-1');
      });
      // Should not throw
      act(() => {
        result.current.handleRawMessage('not json at all {{{');
      });

      expect(useExpertTeamStore.getState().teams).toEqual({});
    });

    it('deduplicates events with seq <= lastSeq in the hook', () => {
      const { result } = renderHook(() =>
        useExpertTeamHandler({ sendAction, isConnected: true }),
      );

      act(() => {
        result.current.subscribe('team-1');
      });
      act(() => {
        result.current.handleRawMessage(JSON.stringify({
          type: 'TEAM_EVENT', teamId: 'team-1', eventType: 'TEAM_STARTED',
          seq: 5, attributes: { goal: 'first' }, timestamp: '2026-01-01T00:00:00Z',
        }));
      });
      act(() => {
        result.current.handleRawMessage(JSON.stringify({
          type: 'TEAM_EVENT', teamId: 'team-1', eventType: 'TEAM_FAILED',
          seq: 3, attributes: {}, timestamp: '2026-01-01T00:00:00Z',
        }));
      });

      // The stale event should be dropped by the hook's own check
      expect(useExpertTeamStore.getState().teams['team-1'].status).toBe('planning');
    });
  });

  describe('reconnection', () => {
    it('re-subscribes with lastSeq on reconnection', () => {
      const { result, rerender } = renderHook(
        (props: UseExpertTeamHandlerOptions) => useExpertTeamHandler(props),
        { initialProps: { sendAction, isConnected: true } as UseExpertTeamHandlerOptions },
      );

      // Subscribe and process an event to advance lastSeq
      act(() => {
        result.current.subscribe('team-1');
      });
      act(() => {
        result.current.handleRawMessage(JSON.stringify({
          type: 'TEAM_EVENT', teamId: 'team-1', eventType: 'TEAM_STARTED',
          seq: 10, attributes: {}, timestamp: '2026-01-01T00:00:00Z',
        }));
      });

      // Simulate disconnection
      rerender({ sendAction, isConnected: false });
      // Simulate reconnection
      rerender({ sendAction, isConnected: true });

      // Should re-subscribe with lastSeq = 10
      expect(sendAction).toHaveBeenCalledWith({
        action: 'subscribeTeam',
        teamId: 'team-1',
        lastSeq: 10,
      });
    });

    it('does not re-subscribe if no team was subscribed', () => {
      const { rerender } = renderHook(
        (props: UseExpertTeamHandlerOptions) => useExpertTeamHandler(props),
        { initialProps: { sendAction, isConnected: true } as UseExpertTeamHandlerOptions },
      );

      // Simulate disconnect/reconnect without subscribing
      rerender({ sendAction, isConnected: false });
      sendAction.mockClear();
      rerender({ sendAction, isConnected: true });

      // Should NOT send subscribeTeam
      expect(sendAction).not.toHaveBeenCalledWith(
        expect.objectContaining({ action: 'subscribeTeam' }),
      );
    });
  });
});
