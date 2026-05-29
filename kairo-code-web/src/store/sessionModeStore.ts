import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type SessionMode = 'agent' | 'experts';

const STORAGE_KEY = 'kairo-session-mode';

interface SessionModeState {
  modes: Record<string, SessionMode>;
  sessionModes: Record<string, SessionMode>;
  getMode: (workspaceId: string) => SessionMode;
  setMode: (workspaceId: string, mode: SessionMode) => void;
  getSessionMode: (sessionId: string) => SessionMode | null;
  setSessionMode: (sessionId: string, mode: SessionMode) => void;
  forgetSessionMode: (sessionId: string) => void;
}

export const useSessionModeStore = create<SessionModeState>()(
  persist(
    (set, get) => ({
      modes: {},
      sessionModes: {},
      getMode: (wid) => get().modes[wid] ?? 'agent',
      setMode: (wid, mode) => set(state => ({
        modes: { ...state.modes, [wid]: mode }
      })),
      getSessionMode: (sid) => get().sessionModes[sid] ?? 'agent',
      setSessionMode: (sid, mode) => set(state => ({
        sessionModes: { ...state.sessionModes, [sid]: mode },
      })),
      forgetSessionMode: (sid) => set(state => {
        if (!(sid in state.sessionModes)) return {};
        const next = { ...state.sessionModes };
        delete next[sid];
        return { sessionModes: next };
      }),
    }),
    {
      name: STORAGE_KEY,
      partialize: (state) => ({ modes: state.modes, sessionModes: state.sessionModes }),
    }
  )
);
