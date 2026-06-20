import { create } from 'zustand';

// ── Types for team event data ───────────────────────────────────────────────

export interface DagNode {
  stepId: string;
  roleId: string;
  instruction: string;
  dependsOn: string[];
  parallelGroup?: string;
}

export interface DagEdge {
  source: string; // stepId
  target: string; // stepId
}

export interface ToolCallEntry {
  toolName: string;
  args: Record<string, unknown>;
  result?: string;
  timestamp: string;
  isError?: boolean;
  durationMs?: number;
}

export interface StepState {
  stepId: string;
  roleId: string;
  status: 'pending' | 'assigned' | 'thinking' | 'working' | 'done' | 'failed';
  thinkingChunks: string[];
  toolCalls: ToolCallEntry[];
  artifact: string;
  thinkingStartedAt: number | null;
  thinkingDuration: number | null;
  evaluation?: {
    verdict: string;
    feedback?: string;
    round?: number;
    maxRounds?: number;
  };
}

export interface CostSnapshot {
  spent: number;
  budget: number;
}

export interface StepPreview {
  stepId: string;
  roleId: string;
  roleName: string;
  instruction: string;
  dependsOn: string[];
  stepIndex: number;
}

export interface TeamState {
  teamId: string;
  goal: string;
  status: 'planning' | 'plan-ready' | 'dispatching' | 'executing' | 'synthesizing' | 'completed' | 'failed' | 'timeout';
  dag: DagNode[];
  edges: DagEdge[];
  steps: Record<string, StepState>;
  cost: CostSnapshot;
  lastSeq: number;
  startedAt?: string;
  completedAt?: string;
  finalOutput?: string;
  warnings: string[];
  planPreview?: { steps: StepPreview[]; estimatedCost?: number; planId?: string };
}

// ── WebSocket event shape (matches backend JSON format) ─────────────────────

export interface TeamWsEvent {
  type: 'TEAM_EVENT';
  teamId: string;
  eventType: string; // TeamEventType value
  seq: number;
  stepId?: string;
  attributes: Record<string, unknown>;
  timestamp: string;
}

// ── Store interface ─────────────────────────────────────────────────────────

export interface ExpertTeamStore {
  teams: Record<string, TeamState>;
  activeTeamId: string | null;
  teamByMessageId: Record<string, string>;
  /**
   * Team that the always-on experts Canvas pane should render. Set when PLAN_READY
   * arrives for an experts-mode session and cleared on session switch / reset.
   * Separate from {@link ExpertTeamStore.activeTeamId} (which the command-palette
   * flow uses) so the Canvas doesn't fight the modal panel for the same slot.
   */
  canvasTeamId: string | null;
  /** Per-session canvas team mapping so switching sessions can restore the Canvas. */
  canvasTeamBySession: Record<string, string>;

  // Actions
  setActiveTeam: (teamId: string | null) => void;
  setCanvasTeamId: (teamId: string | null, sessionId?: string) => void;
  handleTeamEvent: (event: TeamWsEvent) => void;
  /**
   * Applies multiple high-frequency events (STEP_THINKING / STEP_ARTIFACT_CHUNK)
   * in a single state update to avoid intermediate React renders.
   */
  handleBatchEvents: (events: TeamWsEvent[]) => void;
  getActiveTeam: () => TeamState | null;
  setTeamForMessage: (messageId: string, teamId: string) => void;
  getTeamByMessageId: (messageId: string) => TeamState | null;
  /** Save current canvasTeamId for the given session, then clear it. */
  saveCanvasForSession: (sessionId: string) => void;
  /** Restore canvasTeamId from a previously saved session mapping. */
  restoreCanvasForSession: (sessionId: string) => void;
  reset: () => void;
}

// ── Helpers ─────────────────────────────────────────────────────────────────

function createEmptyTeam(teamId: string): TeamState {
  return {
    teamId,
    goal: '',
    status: 'planning',
    dag: [],
    edges: [],
    steps: {},
    cost: { spent: 0, budget: 0 },
    lastSeq: 0,
    warnings: [],
  };
}

/** A blank step, used when a STEP_* event arrives before STEP_ASSIGNED so we never drop it. */
function createEmptyStep(stepId: string, roleId = ''): StepState {
  return {
    stepId,
    roleId,
    status: 'assigned',
    thinkingChunks: [],
    toolCalls: [],
    artifact: '',
    thinkingStartedAt: null,
    thinkingDuration: null,
  };
}

// ── Store ───────────────────────────────────────────────────────────────────

// ── Derived tool summary utility ─────────────────────────────────────────────

function isReadTool(name: string): boolean {
  return ['read', 'read_file', 'cat', 'view'].some((k) => name.toLowerCase().includes(k));
}

function isWriteTool(name: string): boolean {
  return ['write', 'edit', 'create', 'patch', 'replace'].some((k) => name.toLowerCase().includes(k));
}

function isSearchTool(name: string): boolean {
  return ['search', 'grep', 'find', 'glob', 'explore'].some((k) => name.toLowerCase().includes(k));
}

export function deriveToolSummary(toolCalls: ToolCallEntry[]) {
  const writtenFiles = new Set<string>();
  for (const t of toolCalls) {
    if (isWriteTool(t.toolName)) {
      const p = (t.args?.file_path ?? t.args?.path ?? '') as string;
      if (p) {
        const short = p.split('/').slice(-2).join('/');
        writtenFiles.add(short);
      }
    }
  }
  return {
    filesRead: toolCalls.filter((t) => isReadTool(t.toolName)).length,
    filesWritten: toolCalls.filter((t) => isWriteTool(t.toolName)).length,
    commandsRun: toolCalls.filter((t) => t.toolName === 'bash' || t.toolName === 'terminal').length,
    searchesPerformed: toolCalls.filter((t) => isSearchTool(t.toolName)).length,
    writtenFiles: [...writtenFiles],
  };
}

// ── Store ───────────────────────────────────────────────────────────────────

export const useExpertTeamStore = create<ExpertTeamStore>((set, get) => ({
  teams: {},
  activeTeamId: null,
  teamByMessageId: {},
  canvasTeamId: null,
  canvasTeamBySession: {},

  setActiveTeam: (teamId) => set({ activeTeamId: teamId }),
  setCanvasTeamId: (teamId, sessionId?) => {
    const update: Partial<ExpertTeamStore> = { canvasTeamId: teamId };
    if (teamId && sessionId) {
      update.canvasTeamBySession = { ...get().canvasTeamBySession, [sessionId]: teamId };
    }
    set(update);
    if (teamId) {
      sessionStorage.setItem('kairo-canvas-team-id', teamId);
    } else {
      sessionStorage.removeItem('kairo-canvas-team-id');
    }

    // Evict old teams to prevent unbounded memory growth.
    // Keep only the 5 most recent teams (by key insertion order).
    const MAX_TEAMS = 5;
    const teams = get().teams;
    const teamIds = Object.keys(teams);
    if (teamIds.length > MAX_TEAMS) {
      const toRemove = teamIds.slice(0, teamIds.length - MAX_TEAMS);
      const trimmed = { ...teams };
      for (const id of toRemove) {
        delete trimmed[id];
      }
      set({ teams: trimmed });
    }
  },

  getActiveTeam: () => {
    const { teams, activeTeamId } = get();
    return activeTeamId ? teams[activeTeamId] ?? null : null;
  },

  setTeamForMessage: (messageId, teamId) =>
    set((state) => ({
      teamByMessageId: { ...state.teamByMessageId, [messageId]: teamId },
    })),

  getTeamByMessageId: (messageId) => {
    const { teams, teamByMessageId } = get();
    const teamId = teamByMessageId[messageId];
    return teamId ? teams[teamId] ?? null : null;
  },

  saveCanvasForSession: (sessionId) => {
    const { canvasTeamId, canvasTeamBySession } = get();
    if (canvasTeamId && sessionId) {
      set({
        canvasTeamBySession: { ...canvasTeamBySession, [sessionId]: canvasTeamId },
        canvasTeamId: null,
      });
    } else {
      set({ canvasTeamId: null });
    }
    sessionStorage.removeItem('kairo-canvas-team-id');
  },

  restoreCanvasForSession: (sessionId) => {
    const { canvasTeamBySession, teams } = get();
    const savedTeamId = sessionId ? canvasTeamBySession[sessionId] : null;
    if (savedTeamId && teams[savedTeamId]) {
      set({ canvasTeamId: savedTeamId });
      sessionStorage.setItem('kairo-canvas-team-id', savedTeamId);
    }
  },

  reset: () => {
    set({ teams: {}, activeTeamId: null, teamByMessageId: {}, canvasTeamId: null, canvasTeamBySession: {} });
    sessionStorage.removeItem('kairo-canvas-team-id');
  },

  handleBatchEvents: (events) => {
    if (events.length === 0) return;
    set((state) => {
      // Group events by teamId to minimize object spreads
      const teamUpdates = new Map<string, TeamWsEvent[]>();
      for (const event of events) {
        const group = teamUpdates.get(event.teamId);
        if (group) {
          group.push(event);
        } else {
          teamUpdates.set(event.teamId, [event]);
        }
      }

      const updatedTeams = { ...state.teams };

      for (const [teamId, teamEvents] of teamUpdates) {
        const team = updatedTeams[teamId] ?? createEmptyTeam(teamId);
        // Clone step map once per team batch
        const updatedSteps = { ...team.steps };
        let maxSeq = team.lastSeq;

        for (const event of teamEvents) {
          const { eventType, seq, stepId, attributes } = event;
          if (seq <= maxSeq) continue; // dedup
          maxSeq = seq;

          if (eventType === 'STEP_THINKING' && stepId) {
            const step = updatedSteps[stepId] ?? createEmptyStep(stepId);
            // Accumulate into existing array reference — clone once at the end
            updatedSteps[stepId] = {
              ...step,
              status: 'thinking',
              thinkingChunks: [
                ...step.thinkingChunks,
                (attributes.text as string) ?? '',
              ],
              thinkingStartedAt: step.thinkingStartedAt ?? Date.now(),
            };
          } else if (eventType === 'STEP_ARTIFACT_CHUNK' && stepId) {
            const step = updatedSteps[stepId] ?? createEmptyStep(stepId);
            updatedSteps[stepId] = {
              ...step,
              artifact:
                step.artifact + ((attributes.chunk as string) ?? ''),
            };
          }
        }

        updatedTeams[teamId] = {
          ...team,
          steps: updatedSteps,
          lastSeq: maxSeq,
        };
      }

      return { ...state, teams: updatedTeams };
    });
  },

  handleTeamEvent: (event) => {
    set((state) => {
      const { teamId, eventType, seq, stepId, attributes, timestamp } = event;

      // Get or create team state
      const team = state.teams[teamId] ?? createEmptyTeam(teamId);

      // Dedup: ignore if seq <= lastSeq
      if (seq <= team.lastSeq) return state;

      // Deep-clone mutable parts so we don't mutate the previous state
      const updatedTeam: TeamState = {
        ...team,
        steps: { ...team.steps },
        lastSeq: seq,
      };

      switch (eventType) {
        case 'TEAM_STARTED':
          updatedTeam.status = 'planning';
          updatedTeam.goal = (attributes.goal as string) ?? '';
          updatedTeam.startedAt = timestamp;
          break;

        case 'PLAN_READY': {
          updatedTeam.status = 'plan-ready';
          if (attributes.goal) {
            updatedTeam.goal = attributes.goal as string;
          }
          const mode = attributes.mode as string;
          if (mode === 'dag' && attributes.steps) {
            const rawSteps = attributes.steps as Array<Record<string, unknown>>;
            updatedTeam.planPreview = {
              steps: rawSteps.map((s) => ({
                stepId: (s.stepId as string) ?? '',
                roleId: (s.roleId as string) ?? '',
                roleName: (s.roleName as string) ?? '',
                instruction: (s.instruction as string) ?? '',
                dependsOn: (s.dependsOn as string[]) ?? [],
                stepIndex: (s.stepIndex as number) ?? 0,
              })),
              planId: attributes.planId as string | undefined,
            };
            // Also populate the DAG for the full panel view
            updatedTeam.dag = updatedTeam.planPreview.steps.map((s) => ({
              stepId: s.stepId,
              roleId: s.roleId,
              instruction: s.instruction,
              dependsOn: s.dependsOn,
            }));
            // Build edges from dependsOn for DAG visualization
            updatedTeam.edges = updatedTeam.dag.flatMap((n) =>
              (n.dependsOn ?? []).map((dep) => ({ source: dep, target: n.stepId })),
            );
          }
          break;
        }

        case 'STEP_ASSIGNED':
          if (stepId) {
            updatedTeam.steps[stepId] = {
              stepId,
              roleId: (attributes.roleId as string) ?? '',
              status: 'assigned',
              thinkingChunks: [],
              toolCalls: [],
              artifact: '',
              thinkingStartedAt: null,
              thinkingDuration: null,
            };
            if (!updatedTeam.dag.some((n) => n.stepId === stepId)) {
              updatedTeam.dag = [
                ...updatedTeam.dag,
                {
                  stepId,
                  roleId: (attributes.roleId as string) ?? '',
                  instruction: (attributes.instruction as string) ?? '',
                  dependsOn: (attributes.dependsOn as string[]) ?? [],
                },
              ];
            }
          }
          updatedTeam.status = 'executing';
          break;

        case 'STEP_THINKING':
          if (stepId) {
            const thinkStep = updatedTeam.steps[stepId] ?? createEmptyStep(stepId);
            updatedTeam.steps[stepId] = {
              ...thinkStep,
              status: 'thinking',
              thinkingChunks: [
                ...thinkStep.thinkingChunks,
                (attributes.text as string) ?? '',
              ],
              thinkingStartedAt: thinkStep.thinkingStartedAt ?? Date.now(),
            };
          }
          break;

        case 'STEP_TOOL_CALL':
          if (stepId) {
            const toolStep = updatedTeam.steps[stepId] ?? createEmptyStep(stepId);
            const computedDuration =
              toolStep.thinkingDuration == null && toolStep.thinkingStartedAt != null
                ? Date.now() - toolStep.thinkingStartedAt
                : toolStep.thinkingDuration;
            updatedTeam.steps[stepId] = {
              ...toolStep,
              status: 'working',
              toolCalls: [
                ...toolStep.toolCalls,
                {
                  toolName: (attributes.toolName as string) ?? '',
                  args: (attributes.args as Record<string, unknown>) ?? {},
                  result: attributes.result as string | undefined,
                  timestamp,
                  isError: (attributes.isError as boolean) ?? false,
                  durationMs: attributes.durationMs as number | undefined,
                },
              ],
              thinkingDuration: computedDuration,
            };
          }
          break;

        case 'STEP_ARTIFACT_CHUNK':
          if (stepId) {
            const artStep = updatedTeam.steps[stepId] ?? createEmptyStep(stepId);
            updatedTeam.steps[stepId] = {
              ...artStep,
              artifact: artStep.artifact + ((attributes.chunk as string) ?? ''),
            };
          }
          break;

        case 'STEP_COMPLETED':
          if (stepId && updatedTeam.steps[stepId]) {
            const doneStep = updatedTeam.steps[stepId];
            const finalDuration =
              doneStep.thinkingDuration == null && doneStep.thinkingStartedAt != null
                ? Date.now() - doneStep.thinkingStartedAt
                : doneStep.thinkingDuration;
            updatedTeam.steps[stepId] = {
              ...doneStep,
              status: 'done',
              artifact:
                (attributes.output as string) ?? doneStep.artifact,
              thinkingDuration: finalDuration,
            };
          }
          break;

        case 'EVALUATION_RESULT':
          if (stepId && updatedTeam.steps[stepId]) {
            updatedTeam.steps[stepId] = {
              ...updatedTeam.steps[stepId],
              evaluation: {
                verdict: (attributes.verdict as string) ?? '',
                feedback: attributes.feedback as string | undefined,
                round: attributes.round as number | undefined,
                maxRounds: attributes.maxRounds as number | undefined,
              },
            };
          }
          break;

        case 'TEAM_COMPLETED':
          updatedTeam.status = 'completed';
          updatedTeam.completedAt = timestamp;
          updatedTeam.finalOutput = attributes.finalOutput as string | undefined;
          break;

        case 'TEAM_FAILED':
          updatedTeam.status = 'failed';
          updatedTeam.completedAt = timestamp;
          break;

        case 'TEAM_TIMEOUT':
          updatedTeam.status = 'timeout';
          updatedTeam.completedAt = timestamp;
          break;

        case 'HANDOFF':
          if (stepId && updatedTeam.steps[stepId]) {
            updatedTeam.steps[stepId] = {
              ...updatedTeam.steps[stepId],
              status: 'failed',
            };
          }
          break;
      }

      // Update cost if present in attributes
      if (attributes.cost) {
        updatedTeam.cost = attributes.cost as CostSnapshot;
      }

      return {
        ...state,
        teams: { ...state.teams, [teamId]: updatedTeam },
      };
    });

    // Persist canvas team snapshot to sessionStorage for refresh recovery
    const { canvasTeamId, teams } = get();
    if (canvasTeamId && teams[canvasTeamId]) {
      try {
        sessionStorage.setItem(
          'kairo-canvas-team-snapshot',
          JSON.stringify(teams[canvasTeamId]),
        );
      } catch {
        // quota exceeded or serialization error — ignore
      }
    }
  },
}));
