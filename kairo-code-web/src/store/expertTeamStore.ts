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
}

export interface StepState {
  stepId: string;
  roleId: string;
  status: 'pending' | 'assigned' | 'thinking' | 'working' | 'done' | 'failed';
  thinkingChunks: string[];
  toolCalls: ToolCallEntry[];
  artifact: string;
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

  // Actions
  setActiveTeam: (teamId: string | null) => void;
  handleTeamEvent: (event: TeamWsEvent) => void;
  /**
   * Applies multiple high-frequency events (STEP_THINKING / STEP_ARTIFACT_CHUNK)
   * in a single state update to avoid intermediate React renders.
   */
  handleBatchEvents: (events: TeamWsEvent[]) => void;
  getActiveTeam: () => TeamState | null;
  setTeamForMessage: (messageId: string, teamId: string) => void;
  getTeamByMessageId: (messageId: string) => TeamState | null;
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

// ── Store ───────────────────────────────────────────────────────────────────

export const useExpertTeamStore = create<ExpertTeamStore>((set, get) => ({
  teams: {},
  activeTeamId: null,
  teamByMessageId: {},

  setActiveTeam: (teamId) => set({ activeTeamId: teamId }),

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

  reset: () => set({ teams: {}, activeTeamId: null, teamByMessageId: {} }),

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

          if (eventType === 'STEP_THINKING' && stepId && updatedSteps[stepId]) {
            const step = updatedSteps[stepId];
            // Accumulate into existing array reference — clone once at the end
            updatedSteps[stepId] = {
              ...step,
              status: 'thinking',
              thinkingChunks: [
                ...step.thinkingChunks,
                (attributes.text as string) ?? '',
              ],
            };
          } else if (eventType === 'STEP_ARTIFACT_CHUNK' && stepId && updatedSteps[stepId]) {
            const step = updatedSteps[stepId];
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
            };
          }
          updatedTeam.status = 'executing';
          break;

        case 'STEP_THINKING':
          if (stepId && updatedTeam.steps[stepId]) {
            updatedTeam.steps[stepId] = {
              ...updatedTeam.steps[stepId],
              status: 'thinking',
              thinkingChunks: [
                ...updatedTeam.steps[stepId].thinkingChunks,
                (attributes.text as string) ?? '',
              ],
            };
          }
          break;

        case 'STEP_TOOL_CALL':
          if (stepId && updatedTeam.steps[stepId]) {
            updatedTeam.steps[stepId] = {
              ...updatedTeam.steps[stepId],
              status: 'working',
              toolCalls: [
                ...updatedTeam.steps[stepId].toolCalls,
                {
                  toolName: (attributes.toolName as string) ?? '',
                  args: (attributes.args as Record<string, unknown>) ?? {},
                  result: attributes.result as string | undefined,
                  timestamp,
                },
              ],
            };
          }
          break;

        case 'STEP_ARTIFACT_CHUNK':
          if (stepId && updatedTeam.steps[stepId]) {
            updatedTeam.steps[stepId] = {
              ...updatedTeam.steps[stepId],
              artifact:
                updatedTeam.steps[stepId].artifact +
                ((attributes.chunk as string) ?? ''),
            };
          }
          break;

        case 'STEP_COMPLETED':
          if (stepId && updatedTeam.steps[stepId]) {
            updatedTeam.steps[stepId] = {
              ...updatedTeam.steps[stepId],
              status: 'done',
              artifact:
                (attributes.output as string) ??
                updatedTeam.steps[stepId].artifact,
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
  },
}));
