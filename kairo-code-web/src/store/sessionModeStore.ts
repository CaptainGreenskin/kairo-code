import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type SessionMode = 'chat' | 'agent' | 'experts';

interface SessionModeState {
  modes: Record<string, SessionMode>;  // workspaceId -> mode
  getMode: (workspaceId: string) => SessionMode;
  setMode: (workspaceId: string, mode: SessionMode) => void;
}

export const useSessionModeStore = create<SessionModeState>()(
  persist(
    (set, get) => ({
      modes: {},
      getMode: (wid) => get().modes[wid] ?? 'chat',
      setMode: (wid, mode) => set(state => ({
        modes: { ...state.modes, [wid]: mode }
      })),
    }),
    { name: 'kairo-session-mode' }
  )
);
