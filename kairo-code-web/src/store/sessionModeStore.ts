import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type SessionMode = 'chat' | 'agent' | 'experts';

interface SessionModeState {
  modes: Record<string, SessionMode>;  // workspaceId -> mode
  /** Monotonically incremented when mode changes while a session exists. App.tsx watches this. */
  sessionRestartRequested: number;
  /** Which workspace triggered the restart request. */
  restartWorkspaceId: string | null;
  getMode: (workspaceId: string) => SessionMode;
  setMode: (workspaceId: string, mode: SessionMode) => void;
  /** Call from SessionModeToggle when an active session exists and mode changes. */
  requestSessionRestart: (workspaceId: string) => void;
}

export const useSessionModeStore = create<SessionModeState>()(
  persist(
    (set, get) => ({
      modes: {},
      sessionRestartRequested: 0,
      restartWorkspaceId: null,
      getMode: (wid) => get().modes[wid] ?? 'chat',
      setMode: (wid, mode) => set(state => ({
        modes: { ...state.modes, [wid]: mode }
      })),
      requestSessionRestart: (wid) => set(state => ({
        sessionRestartRequested: state.sessionRestartRequested + 1,
        restartWorkspaceId: wid,
      })),
    }),
    {
      name: 'kairo-session-mode',
      // Only persist modes, not the transient restart signal
      partialize: (state) => ({ modes: state.modes }),
    }
  )
);
