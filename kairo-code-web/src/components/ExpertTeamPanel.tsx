import React, { useMemo, useCallback, useState, useRef } from 'react';
import {
  ReactFlow,
  Node,
  Edge,
  Background,
  Controls,
  type NodeProps,
  Handle,
  Position,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { useExpertTeamStore, TeamState, DagNode, ToolCallEntry, deriveToolSummary } from '../store/expertTeamStore';
import { ExpertTooltip, ExpertTooltipData } from './ExpertTooltip';
import { RejectFeedbackModal } from './RejectFeedbackModal';
import { SynthesizerCard } from './SynthesizerCard';
import { ExpertStepCard } from './ExpertStepCard';
import { useThinkingTimer } from '../hooks/useThinkingTimer';

// ── Role metadata ────────────────────────────────────────────────────────────

const ROLE_META: Record<string, { icon: string; label: string }> = {
  architect: { icon: '🏗️', label: 'Architect' },
  researcher: { icon: '🔍', label: 'Researcher' },
  coder: { icon: '💻', label: 'Coder' },
  reviewer: { icon: '👀', label: 'Reviewer' },
  tester: { icon: '🧪', label: 'Tester' },
  synthesizer: { icon: '📋', label: 'Synthesizer' },
};

function getRoleMeta(roleId: string) {
  return ROLE_META[roleId.toLowerCase()] ?? { icon: '⚙️', label: roleId };
}

// ── Status colors ────────────────────────────────────────────────────────────

const STATUS_COLORS: Record<string, { border: string; bg: string; text: string }> = {
  pending: { border: 'border-gray-500', bg: 'bg-gray-500/10', text: 'text-gray-400' },
  assigned: { border: 'border-blue-500', bg: 'bg-blue-500/10', text: 'text-blue-400' },
  thinking: { border: 'border-blue-400', bg: 'bg-blue-400/10', text: 'text-blue-300' },
  working: { border: 'border-amber-500', bg: 'bg-amber-500/10', text: 'text-amber-400' },
  done: { border: 'border-green-500', bg: 'bg-green-500/10', text: 'text-green-400' },
  failed: { border: 'border-red-500', bg: 'bg-red-500/10', text: 'text-red-400' },
};

function getStatusColors(status: string) {
  return STATUS_COLORS[status] ?? STATUS_COLORS.pending;
}

// ── Custom node component ────────────────────────────────────────────────────

function ExpertNode({ data }: NodeProps) {
  const { roleId, status, label, selected, instruction, stepId: _stepId, thinkingStartedAt, toolCalls, thinkingDuration } = data as {
    roleId: string;
    status: string;
    label: string;
    selected: boolean;
    instruction?: string;
    stepId?: string;
    thinkingStartedAt?: number | null;
    toolCalls?: ToolCallEntry[];
    thinkingDuration?: number | null;
  };
  const meta = getRoleMeta(roleId);
  const colors = getStatusColors(status);
  const isActive = status === 'thinking' || status === 'working' || status === 'assigned';
  const [hovered, setHovered] = useState(false);
  const nodeRef = useRef<HTMLDivElement>(null);
  const thinkingTime = useThinkingTimer(thinkingStartedAt ?? null, status === 'thinking');

  const tooltipData: ExpertTooltipData = {
    roleId,
    roleName: label || meta.label,
    instruction,
    status,
  };

  // Build compact status text
  let statusText: React.ReactNode = null;
  if (status === 'thinking') {
    statusText = (
      <span className="text-[10px] text-blue-300 animate-pulse">
        Thought · {thinkingTime || '0s'}
      </span>
    );
  } else if (status === 'working' && toolCalls && toolCalls.length > 0) {
    const summary = deriveToolSummary(toolCalls);
    const parts: string[] = [];
    if (summary.filesRead > 0) parts.push(`Read ${summary.filesRead}`);
    if (summary.filesWritten > 0) parts.push(`Write ${summary.filesWritten}`);
    if (summary.commandsRun > 0) parts.push(`Cmd ${summary.commandsRun}`);
    if (summary.searchesPerformed > 0) parts.push(`Search ${summary.searchesPerformed}`);
    if (parts.length > 0) {
      statusText = (
        <span className="text-[10px] text-amber-300">{parts.join(' · ')}</span>
      );
    }
  } else if (status === 'done') {
    const dur = thinkingDuration ? `${Math.round(thinkingDuration / 1000)}s` : '';
    statusText = (
      <span className="text-[10px] text-green-400">✓{dur ? ` ${dur}` : ''}</span>
    );
  }

  return (
    <div
      ref={nodeRef}
      className={`
        relative px-3 py-2 rounded-lg border-2 ${colors.border} ${colors.bg}
        min-w-[120px] text-center transition-all duration-200
        ${selected ? 'ring-2 ring-[var(--accent)] ring-offset-1 ring-offset-[var(--bg-primary)]' : ''}
        ${isActive ? 'animate-pulse' : ''}
      `}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      <Handle type="target" position={Position.Top} className="!bg-[var(--border)]" />
      <div className="text-lg leading-none mb-1">{meta.icon}</div>
      <div className="text-xs font-medium text-[var(--text-primary)] truncate">{label || meta.label}</div>
      <div className={`text-[10px] mt-0.5 capitalize ${colors.text}`}>{status}</div>
      {statusText && <div className="mt-0.5">{statusText}</div>}
      <Handle type="source" position={Position.Bottom} className="!bg-[var(--border)]" />
      <ExpertTooltip
        data={tooltipData}
        position={{ x: 130, y: -10 }}
        visible={hovered}
      />
    </div>
  );
}

const nodeTypes = { expert: ExpertNode };

// ── Auto-layout (simple layered) ─────────────────────────────────────────────

function computeDepthMap(dag: DagNode[]): Map<string, number> {
  const depthMap = new Map<string, number>();
  const visited = new Set<string>();

  function dfs(stepId: string): number {
    if (depthMap.has(stepId)) return depthMap.get(stepId)!;
    if (visited.has(stepId)) return 0; // cycle guard
    visited.add(stepId);

    const node = dag.find(n => n.stepId === stepId);
    if (!node || node.dependsOn.length === 0) {
      depthMap.set(stepId, 0);
      return 0;
    }

    const maxParentDepth = Math.max(...node.dependsOn.map(dep => dfs(dep)));
    const depth = maxParentDepth + 1;
    depthMap.set(stepId, depth);
    return depth;
  }

  dag.forEach(n => dfs(n.stepId));
  return depthMap;
}

function buildFlowElements(
  team: TeamState | undefined,
  selectedStepId: string | null,
): { nodes: Node[]; edges: Edge[] } {
  if (!team || team.dag.length === 0) {
    return { nodes: [], edges: [] };
  }

  const depthMap = computeDepthMap(team.dag);

  // Group nodes by depth layer
  const layers = new Map<number, DagNode[]>();
  team.dag.forEach(n => {
    const depth = depthMap.get(n.stepId) ?? 0;
    if (!layers.has(depth)) layers.set(depth, []);
    layers.get(depth)!.push(n);
  });

  const nodes: Node[] = [];
  layers.forEach((layerNodes, layerIndex) => {
    layerNodes.forEach((dagNode, nodeIndex) => {
      const step = team.steps[dagNode.stepId];
      nodes.push({
        id: dagNode.stepId,
        type: 'expert',
        position: { x: layerIndex * 250, y: nodeIndex * 120 },
        data: {
          roleId: dagNode.roleId,
          status: step?.status ?? 'pending',
          label: getRoleMeta(dagNode.roleId).label,
          selected: dagNode.stepId === selectedStepId,
          instruction: dagNode.instruction,
          stepId: dagNode.stepId,
          thinkingStartedAt: step?.thinkingStartedAt ?? null,
          toolCalls: step?.toolCalls ?? [],
          thinkingDuration: step?.thinkingDuration ?? null,
        },
      });
    });
  });

  // Build edges from team.edges or dagNode.dependsOn
  const edges: Edge[] = [];
  if (team.edges.length > 0) {
    team.edges.forEach((e, i) => {
      const sourceStep = team.steps[e.source];
      edges.push({
        id: `edge-${i}`,
        source: e.source,
        target: e.target,
        animated: sourceStep?.status === 'working' || sourceStep?.status === 'thinking',
        style: { stroke: 'var(--border)' },
      });
    });
  } else {
    team.dag.forEach(n => {
      n.dependsOn.forEach((dep, i) => {
        const sourceStep = team.steps[dep];
        edges.push({
          id: `edge-${n.stepId}-${dep}-${i}`,
          source: dep,
          target: n.stepId,
          animated: sourceStep?.status === 'working' || sourceStep?.status === 'thinking',
          style: { stroke: 'var(--border)' },
        });
      });
    });
  }

  return { nodes, edges };
}

// ── TopBar ───────────────────────────────────────────────────────────────────

function TopBar({ team }: { team: TeamState | undefined }) {
  if (!team) return null;

  const steps = Object.values(team.steps);
  const total = team.dag.length || steps.length;
  const completed = steps.filter(s => s.status === 'done').length;
  const failed = steps.filter(s => s.status === 'failed').length;

  const costPct = team.cost.budget > 0
    ? Math.min((team.cost.spent / team.cost.budget) * 100, 100)
    : 0;

  const teamStatusColor: Record<string, string> = {
    planning: 'bg-blue-500/20 text-blue-400',
    executing: 'bg-amber-500/20 text-amber-400',
    completed: 'bg-green-500/20 text-green-400',
    failed: 'bg-red-500/20 text-red-400',
    timeout: 'bg-orange-500/20 text-orange-400',
  };

  return (
    <div className="flex items-center justify-between px-4 py-2 border-b border-[var(--border)]
                    bg-[var(--bg-secondary)]">
      <div className="flex items-center gap-4">
        <span className={`text-[10px] font-medium px-2 py-0.5 rounded-full uppercase
          ${teamStatusColor[team.status] ?? 'bg-gray-500/20 text-gray-400'}`}>
          {team.status}
        </span>
        <span className="text-xs text-[var(--text-secondary)]">
          Progress: <span className="font-medium text-[var(--text-primary)]">{completed}/{total}</span>
          {failed > 0 && <span className="text-red-400 ml-1">({failed} failed)</span>}
        </span>
      </div>
      <div className="flex items-center gap-3">
        {team.cost.budget > 0 && (
          <div className="flex items-center gap-2">
            <span className="text-xs text-[var(--text-muted)]">Cost:</span>
            <div className="w-20 h-1.5 rounded-full bg-[var(--bg-primary)] overflow-hidden">
              <div
                className={`h-full rounded-full transition-all duration-300 ${
                  costPct > 80 ? 'bg-red-500' : costPct > 50 ? 'bg-amber-500' : 'bg-green-500'
                }`}
                style={{ width: `${costPct}%` }}
              />
            </div>
            <span className="text-[10px] text-[var(--text-muted)]">
              ${team.cost.spent.toFixed(2)}/${team.cost.budget.toFixed(2)}
            </span>
          </div>
        )}
      </div>
    </div>
  );
}



// ── EmptyState ───────────────────────────────────────────────────────────────

function EmptyState({ message }: { message: string }) {
  return (
    <div className="flex items-center justify-center h-full">
      <p className="text-xs text-[var(--text-muted)]">{message}</p>
    </div>
  );
}

// ── Main component ───────────────────────────────────────────────────────────

export interface ExpertTeamPanelProps {
  teamId: string;
  readOnly?: boolean;
  /** Send a JSON payload over the shared WebSocket. */
  sendAction?: (payload: Record<string, unknown>) => boolean;
  /** Called when user clicks Replay on the SynthesizerCard. */
  onReplay?: () => void;
}

export function ExpertTeamPanel({ teamId, readOnly: _readOnly = false, sendAction, onReplay }: ExpertTeamPanelProps) {
  const team = useExpertTeamStore(state => state.teams[teamId]);
  const [selectedStepId, setSelectedStepId] = useState<string | null>(null);
  const [rejectModal, setRejectModal] = useState<{ visible: boolean; stepId: string; roleName: string }>({
    visible: false, stepId: '', roleName: '',
  });

  // Build ReactFlow elements from team state
  const { nodes, edges } = useMemo(
    () => buildFlowElements(team, selectedStepId),
    [team, selectedStepId],
  );

  // Selected step detail
  const selectedStep = selectedStepId ? team?.steps[selectedStepId] : null;

  const onNodeClick = useCallback((_: React.MouseEvent, node: Node) => {
    setSelectedStepId(node.id);
  }, []);

  const _handleRejectOpen = useCallback((stepId: string, _roleName: string) => {
    setRejectModal({ visible: true, stepId, roleName: _roleName });
  }, []);
  void _handleRejectOpen; // suppress unused warning

  const handleRejectSubmit = useCallback((feedback: string) => {
    if (sendAction) {
      sendAction({ action: 'rejectStep', teamId, stepId: rejectModal.stepId, feedback });
    }
    setRejectModal({ visible: false, stepId: '', roleName: '' });
  }, [teamId, rejectModal.stepId, sendAction]);

  const handleRejectCancel = useCallback(() => {
    setRejectModal({ visible: false, stepId: '', roleName: '' });
  }, []);

  if (!team) {
    return (
      <div className="flex items-center justify-center h-full bg-[var(--bg-primary)]">
        <p className="text-xs text-[var(--text-muted)]">No team data for: {teamId}</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full bg-[var(--bg-primary)] border border-[var(--border)] rounded-lg overflow-hidden">
      {/* Top bar with progress + cost */}
      <TopBar team={team} />

      {/* Synthesizer card when completed */}
      {team.status === 'completed' && team.finalOutput && (
        <div className="p-4 overflow-y-auto">
          <SynthesizerCard
            finalOutput={team.finalOutput}
            cost={team.cost}
            teamId={teamId}
            completedAt={team.completedAt}
            startedAt={team.startedAt}
            onReplay={onReplay}
          />
        </div>
      )}

      {/* Main content: DAG + Detail (hidden when synthesizer card is shown) */}
      {!(team.status === 'completed' && team.finalOutput) && (
        <div className="flex flex-1 overflow-hidden">
          {/* Left: DAG view */}
          <div className="flex-[3] border-r border-[var(--border)] relative">
            {nodes.length > 0 ? (
              <ReactFlow
                nodes={nodes}
                edges={edges}
                onNodeClick={onNodeClick}
                nodeTypes={nodeTypes}
                fitView
                proOptions={{ hideAttribution: true }}
                minZoom={0.3}
                maxZoom={1.5}
              >
                <Background gap={16} size={1} />
                <Controls
                  showInteractive={false}
                  className="!bg-[var(--bg-secondary)] !border-[var(--border)] !shadow-lg"
                />
              </ReactFlow>
            ) : (
              <EmptyState message={team.status === 'planning' ? 'Planning DAG...' : 'No steps defined'} />
            )}
          </div>

          {/* Right: Detail panel */}
          <div className="flex-[2] flex flex-col overflow-hidden">
            {selectedStep ? (
              <div className="flex flex-col h-full overflow-y-auto p-3">
                <ExpertStepCard step={selectedStep} defaultExpanded={true} />
              </div>
            ) : (
              <EmptyState message="Click an expert node to see details" />
            )}
          </div>
        </div>
      )}

      {/* Reject Feedback Modal */}
      <RejectFeedbackModal
        roleName={rejectModal.roleName}
        stepId={rejectModal.stepId}
        visible={rejectModal.visible}
        onSubmit={handleRejectSubmit}
        onCancel={handleRejectCancel}
      />
    </div>
  );
}
