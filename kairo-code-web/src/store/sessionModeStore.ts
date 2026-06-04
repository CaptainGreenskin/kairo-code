import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type SessionMode = 'agent' | 'experts';

const STORAGE_KEY = 'kairo-session-mode';

interface SessionModeState {
  /** Per-session locked mode (set once at session creation, immutable after). */
  sessionModes: Record<string, SessionMode>;
  /** Pending mode for the NEXT new session. Reset to 'agent' after each session creation. */
  pendingMode: SessionMode;
  getPendingMode: () => SessionMode;
  setPendingMode: (mode: SessionMode) => void;
  getSessionMode: (sessionId: string) => SessionMode | null;
  setSessionMode: (sessionId: string, mode: SessionMode) => void;
  forgetSessionMode: (sessionId: string) => void;
  /** @deprecated Compatibility shim — returns pendingMode */
  getMode: (workspaceId: string) => SessionMode;
  /** @deprecated Compatibility shim — sets pendingMode */
  setMode: (workspaceId: string, mode: SessionMode) => void;
}

export const useSessionModeStore = create<SessionModeState>()(
  persist(
    (set, get) => ({
      sessionModes: {},
      pendingMode: 'agent' as SessionMode,
      getPendingMode: () => get().pendingMode,
      setPendingMode: (mode) => set({ pendingMode: mode }),
      getSessionMode: (sid) => get().sessionModes[sid] ?? null,
      setSessionMode: (sid, mode) => set(state => ({
        sessionModes: { ...state.sessionModes, [sid]: mode },
        pendingMode: 'agent',
      })),
      forgetSessionMode: (sid) => set(state => {
        if (!(sid in state.sessionModes)) return {};
        const next = { ...state.sessionModes };
        delete next[sid];
        return { sessionModes: next };
      }),
      getMode: (_wid) => get().pendingMode,
      setMode: (_wid, mode) => set({ pendingMode: mode }),
    }),
    {
      name: STORAGE_KEY,
      partialize: (state) => ({ sessionModes: state.sessionModes, pendingMode: 'agent' }),
    }
  )
);
