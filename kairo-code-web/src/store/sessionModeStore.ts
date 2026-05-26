import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type SessionMode = 'agent' | 'experts' | 'team';

interface SessionModeState {
  modes: Record<string, SessionMode>;  // workspaceId -> mode (used at create time)
  sessionModes: Record<string, SessionMode>;  // sessionId -> mode (fixed at create)
  /** Monotonically incremented when mode changes while a session exists. App.tsx watches this. */
  sessionRestartRequested: number;
  /** Which workspace triggered the restart request. */
  restartWorkspaceId: string | null;
  getMode: (workspaceId: string) => SessionMode;
  setMode: (workspaceId: string, mode: SessionMode) => void;
  /** Get the mode a session was created with. Returns null if unknown. */
  getSessionMode: (sessionId: string) => SessionMode | null;
  /** Record the mode used to create a session. Called after SESSION_CREATED arrives. */
  setSessionMode: (sessionId: string, mode: SessionMode) => void;
  /** Drop a session's mode entry (e.g. when its tab is closed). */
  forgetSessionMode: (sessionId: string) => void;
  /** Call from SessionModeToggle when an active session exists and mode changes. */
  requestSessionRestart: (workspaceId: string) => void;
}

export const useSessionModeStore = create<SessionModeState>()(
  persist(
    (set, get) => ({
      modes: {},
      sessionModes: {},
      sessionRestartRequested: 0,
      restartWorkspaceId: null,
      getMode: (wid) => {
        const stored = get().modes[wid] as string | undefined;
        // v2.3 mode collapse: legacy "chat" in persisted state maps to "agent"
        if (stored === 'chat') return 'agent';
        return (stored as SessionMode | undefined) ?? 'agent';
      },
      setMode: (wid, mode) => set(state => ({
        modes: { ...state.modes, [wid]: mode }
      })),
      getSessionMode: (sid) => get().sessionModes[sid] ?? null,
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
      name: 'kairo-session-mode',
      // Persist both workspace defaults and per-session mode; skip transient restart signal
      partialize: (state) => ({ modes: state.modes, sessionModes: state.sessionModes }),
    }
  )
);
