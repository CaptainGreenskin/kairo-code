import { describe, it, expect, beforeEach } from 'vitest';
import { useSessionModeStore } from './sessionModeStore';

describe('sessionModeStore', () => {
  beforeEach(() => {
    // Reset store state
    useSessionModeStore.setState({ modes: {} });
  });

  it('default mode is chat', () => {
    const mode = useSessionModeStore.getState().getMode('ws-1');
    expect(mode).toBe('chat');
  });

  it('setMode persists per workspace', () => {
    useSessionModeStore.getState().setMode('ws-1', 'experts');
    const mode = useSessionModeStore.getState().getMode('ws-1');
    expect(mode).toBe('experts');
  });

  it('different workspaces have independent modes', () => {
    useSessionModeStore.getState().setMode('ws-1', 'experts');
    useSessionModeStore.getState().setMode('ws-2', 'agent');
    expect(useSessionModeStore.getState().getMode('ws-1')).toBe('experts');
    expect(useSessionModeStore.getState().getMode('ws-2')).toBe('agent');
  });

  it('getMode returns chat for unknown workspace', () => {
    useSessionModeStore.getState().setMode('ws-1', 'experts');
    expect(useSessionModeStore.getState().getMode('ws-unknown')).toBe('chat');
  });

  it('setMode overwrites previous value', () => {
    useSessionModeStore.getState().setMode('ws-1', 'experts');
    useSessionModeStore.getState().setMode('ws-1', 'chat');
    expect(useSessionModeStore.getState().getMode('ws-1')).toBe('chat');
  });

  it('modes map is initially empty', () => {
    expect(useSessionModeStore.getState().modes).toEqual({});
  });
});
