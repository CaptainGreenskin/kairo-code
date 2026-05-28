import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type SessionMode = 'agent';

const STORAGE_KEY = 'kairo-session-mode';

interface SessionModeState {
  modes: Record<string, SessionMode>;
  sessionModes: Record<string, SessionMode>;
  sessionRestartRequested: number;
  restartWorkspaceId: string | null;
  getMode: (workspaceId: string) => SessionMode;
  setMode: (workspaceId: string, mode: SessionMode) => void;
  getSessionMode: (sessionId: string) => SessionMode | null;
  setSessionMode: (sessionId: string, mode: SessionMode) => void;
  forgetSessionMode: (sessionId: string) => void;
  requestSessionRestart: (workspaceId: string) => void;
}

export const useSessionModeStore = create<SessionModeState>()(
  persist(
    (set) => ({
      modes: {},
      sessionModes: {},
      sessionRestartRequested: 0,
      restartWorkspaceId: null,
      getMode: () => 'agent',
      setMode: (wid, mode) => set(state => ({
        modes: { ...state.modes, [wid]: mode }
      })),
      getSessionMode: () => 'agent',
      setSessionMode: (sid, mode) => set(state => ({
        sessionModes: { ...state.sessionModes, [sid]: mode },
      })),
      forgetSessionMode: (sid) => set(state => {
        if (!(sid in state.sessionModes)) return {};
        const next = { ...state.sessionModes };
        delete next[sid];
        return { sessionModes: next };
      }),
      requestSessionRestart: (wid) => set(state => ({
        sessionRestartRequested: state.sessionRestartRequested + 1,
        restartWorkspaceId: wid,
      })),
    }),
    {
      name: STORAGE_KEY,
      partialize: (state) => ({ modes: state.modes, sessionModes: state.sessionModes }),
    }
  )
);
