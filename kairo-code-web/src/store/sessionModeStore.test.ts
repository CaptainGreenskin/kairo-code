import { describe, it, expect, beforeEach } from 'vitest';
import { useSessionModeStore, type SessionMode } from './sessionModeStore';

describe('sessionModeStore', () => {
  beforeEach(() => {
    // Reset store state
    useSessionModeStore.setState({ modes: {} });
  });

  it('default mode is agent', () => {
    const mode = useSessionModeStore.getState().getMode('ws-1');
    expect(mode).toBe('agent');
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

  it('getMode returns agent for unknown workspace', () => {
    useSessionModeStore.getState().setMode('ws-1', 'experts');
    expect(useSessionModeStore.getState().getMode('ws-unknown')).toBe('agent');
  });

  it('setMode overwrites previous value', () => {
    useSessionModeStore.getState().setMode('ws-1', 'experts');
    useSessionModeStore.getState().setMode('ws-1', 'agent');
    expect(useSessionModeStore.getState().getMode('ws-1')).toBe('agent');
  });

  it('modes map is initially empty', () => {
    expect(useSessionModeStore.getState().modes).toEqual({});
  });

  it('v2.3 migration: legacy "chat" in storage maps to "agent"', () => {
    // Simulate a legacy persisted state where mode was stored as 'chat'
    useSessionModeStore.setState({ modes: { 'ws-legacy': 'chat' as unknown as SessionMode } });
    expect(useSessionModeStore.getState().getMode('ws-legacy')).toBe('agent');
  });
});
