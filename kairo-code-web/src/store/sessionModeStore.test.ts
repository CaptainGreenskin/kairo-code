import { describe, it, expect, beforeEach } from 'vitest';
import { useSessionModeStore } from './sessionModeStore';

describe('sessionModeStore', () => {
  beforeEach(() => {
    useSessionModeStore.setState({ modes: {} });
  });

  it('default mode is always agent', () => {
    const mode = useSessionModeStore.getState().getMode('ws-1');
    expect(mode).toBe('agent');
  });

  it('getMode always returns agent regardless of workspace', () => {
    useSessionModeStore.getState().setMode('ws-1', 'agent');
    expect(useSessionModeStore.getState().getMode('ws-1')).toBe('agent');
    expect(useSessionModeStore.getState().getMode('ws-unknown')).toBe('agent');
  });

  it('getSessionMode always returns agent', () => {
    expect(useSessionModeStore.getState().getSessionMode('sid-1')).toBe('agent');
  });

  it('modes map is initially empty', () => {
    expect(useSessionModeStore.getState().modes).toEqual({});
  });
});
