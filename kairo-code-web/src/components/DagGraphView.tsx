import { useMemo, useState } from 'react';
import {
  ReactFlow,
  type Node,
  type Edge,
  type NodeProps,
  Handle,
  Position,
  ReactFlowProvider,
  Controls,
  Background,
  BackgroundVariant,
  MarkerType,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import type { DagNode, StepState } from '../store/expertTeamStore';
import { Maximize2, Minimize2 } from 'lucide-react';

// ── Role metadata ───────────────────────────────────────────────────────────

const ROLE_META: Record<string, { icon: string; label: string; color: string }> = {
  architect: { icon: '🏗️', label: 'Architect', color: '#8b5cf6' },
  researcher: { icon: '🔍', label: 'Researcher', color: '#06b6d4' },
  coder: { icon: '💻', label: 'Coder', color: '#3b82f6' },
  reviewer: { icon: '👀', label: 'Reviewer', color: '#f59e0b' },
  tester: { icon: '🧪', label: 'Tester', color: '#10b981' },
  synthesizer: { icon: '📋', label: 'Synthesizer', color: '#ec4899' },
};

function getRoleMeta(roleId: string) {
  const key = roleId.includes(':') ? roleId.split(':').pop()!.toLowerCase() : roleId.toLowerCase();
  return ROLE_META[key] ?? { icon: '⚙️', label: roleId, color: '#6b7280' };
}

const STATUS_CONFIG: Record<string, { glow: string; badge: string; badgeText: string; pulse: boolean }> = {
  pending:  { glow: 'shadow-gray-800/30',   badge: 'bg-gray-600/30',   badgeText: 'text-gray-400',  pulse: false },
  assigned: { glow: 'shadow-blue-500/20',    badge: 'bg-blue-500/25',   badgeText: 'text-blue-400',  pulse: false },
  thinking: { glow: 'shadow-blue-400/40',    badge: 'bg-blue-400/25',   badgeText: 'text-blue-300',  pulse: true },
  working:  { glow: 'shadow-amber-500/40',   badge: 'bg-amber-500/25',  badgeText: 'text-amber-400', pulse: true },
  done:     { glow: 'shadow-green-500/30',   badge: 'bg-green-500/25',  badgeText: 'text-green-400', pulse: false },
  failed:   { glow: 'shadow-red-500/30',     badge: 'bg-red-500/25',    badgeText: 'text-red-400',   pulse: false },
};

// ── Custom DAG Node ─────────────────────────────────────────────────────────

type DagNodeData = {
  roleId: string;
  instruction: string;
  status: string;
  onOpen: () => void;
};

function DagStepNode({ data }: NodeProps<Node<DagNodeData>>) {
  const { roleId, instruction, status, onOpen } = data;
  const role = getRoleMeta(roleId);
  const cfg = STATUS_CONFIG[status] ?? STATUS_CONFIG.pending;

  return (
    <>
      <Handle type="target" position={Position.Top} className="!w-2.5 !h-2.5 !bg-gray-500 !border-gray-600 !-top-1" />
      <div
        className={`rounded-xl border border-gray-700/60 bg-gradient-to-br from-gray-800/90 to-gray-900/95
          backdrop-blur-sm px-3 py-2.5 w-[200px] cursor-pointer
          hover:border-gray-500/80 hover:scale-[1.03] transition-all duration-200
          shadow-lg ${cfg.glow} ${cfg.pulse ? 'animate-pulse' : ''}`}
        style={{ borderLeftColor: role.color, borderLeftWidth: 3 }}
        onClick={onOpen}
      >
        <div className="flex items-center gap-2 mb-1.5">
          <span className="text-base leading-none">{role.icon}</span>
          <span className="text-xs font-semibold text-gray-200 tracking-wide">{role.label}</span>
          <span className={`ml-auto text-[9px] font-medium px-1.5 py-0.5 rounded-full uppercase tracking-wider ${cfg.badge} ${cfg.badgeText}`}>
            {status}
          </span>
        </div>
        <p className="text-[11px] text-gray-400 line-clamp-2 leading-relaxed">
          {instruction || 'Waiting to start...'}
        </p>
      </div>
      <Handle type="source" position={Position.Bottom} className="!w-2.5 !h-2.5 !bg-gray-500 !border-gray-600 !-bottom-1" />
    </>
  );
}

const nodeTypes = { dagStep: DagStepNode };

// ── Layout algorithm (topological layering) ─────────────────────────────────

const NODE_WIDTH = 200;
const NODE_HEIGHT = 80;
const GAP_X = 50;
const GAP_Y = 90;

function computeLayout(
  dagNodes: DagNode[],
  steps: Record<string, StepState>,
  onOpen: (stepId: string) => void,
): { nodes: Node[]; edges: Edge[] } {
  if (dagNodes.length === 0) return { nodes: [], edges: [] };

  const idSet = new Set(dagNodes.map((n) => n.stepId));
  const inDegree = new Map<string, number>();
  for (const n of dagNodes) {
    const validDeps = (n.dependsOn ?? []).filter((d) => idSet.has(d));
    inDegree.set(n.stepId, validDeps.length);
  }

  // Kahn's topological layering
  const layers: string[][] = [];
  const remaining = new Set(idSet);

  while (remaining.size > 0) {
    const layer = Array.from(remaining).filter((id) => (inDegree.get(id) ?? 0) === 0);
    if (layer.length === 0) {
      layers.push(Array.from(remaining));
      break;
    }
    layers.push(layer);
    for (const id of layer) {
      remaining.delete(id);
      for (const n of dagNodes) {
        if (n.dependsOn?.includes(id)) {
          inDegree.set(n.stepId, (inDegree.get(n.stepId) ?? 1) - 1);
        }
      }
    }
  }

  // Position: center each layer horizontally
  const maxLayerWidth = Math.max(...layers.map((l) => l.length));
  const totalWidth = maxLayerWidth * (NODE_WIDTH + GAP_X);
  const rfNodes: Node[] = [];

  for (let layerIdx = 0; layerIdx < layers.length; layerIdx++) {
    const layer = layers[layerIdx];
    const layerWidth = layer.length * (NODE_WIDTH + GAP_X) - GAP_X;
    const offsetX = (totalWidth - layerWidth) / 2;

    for (let colIdx = 0; colIdx < layer.length; colIdx++) {
      const stepId = layer[colIdx];
      const dagNode = dagNodes.find((n) => n.stepId === stepId)!;
      const step = steps[stepId];
      const status = step?.status ?? 'pending';

      rfNodes.push({
        id: stepId,
        type: 'dagStep',
        position: {
          x: offsetX + colIdx * (NODE_WIDTH + GAP_X),
          y: layerIdx * (NODE_HEIGHT + GAP_Y),
        },
        data: {
          roleId: dagNode.roleId,
          instruction: dagNode.instruction,
          status,
          onOpen: () => onOpen(stepId),
        },
      });
    }
  }

  // Edges
  const rfEdges: Edge[] = [];
  for (const n of dagNodes) {
    for (const dep of n.dependsOn ?? []) {
      if (idSet.has(dep)) {
        const depStep = steps[dep];
        const isDone = depStep?.status === 'done';
        rfEdges.push({
          id: `${dep}->${n.stepId}`,
          source: dep,
          target: n.stepId,
          type: 'smoothstep',
          animated: !isDone,
          markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16, color: isDone ? '#22c55e' : '#4b5563' },
          style: {
            stroke: isDone ? '#22c55e' : '#4b5563',
            strokeWidth: isDone ? 2.5 : 1.5,
          },
        });
      }
    }
  }

  return { nodes: rfNodes, edges: rfEdges };
}

// ── Main component ──────────────────────────────────────────────────────────

interface DagGraphViewProps {
  dag: DagNode[];
  steps: Record<string, StepState>;
  onOpenStep: (stepId: string) => void;
}

function DagGraphInner({ dag, steps, onOpenStep, isModal = false }: DagGraphViewProps & { isModal?: boolean }) {
  const { nodes, edges } = useMemo(
    () => computeLayout(dag, steps, onOpenStep),
    [dag, steps, onOpenStep],
  );

  if (nodes.length === 0) return null;

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      fitView
      fitViewOptions={{ padding: isModal ? 0.3 : 0.15 }}
      nodesDraggable={false}
      nodesConnectable={false}
      elementsSelectable={false}
      panOnDrag
      zoomOnScroll
      zoomOnPinch
      preventScrolling={false}
      minZoom={0.2}
      maxZoom={2.5}
      proOptions={{ hideAttribution: true }}
      className="!bg-transparent"
    >
      <Background variant={BackgroundVariant.Dots} gap={16} size={1} color="#374151" />
      {isModal && (
        <Controls
          showInteractive={false}
          className="!bg-gray-800/80 !border-gray-700 !rounded-lg !shadow-lg [&>button]:!bg-gray-800 [&>button]:!border-gray-700 [&>button]:!text-gray-300 [&>button:hover]:!bg-gray-700"
        />
      )}
    </ReactFlow>
  );
}

export function DagGraphView({ dag, steps, onOpenStep }: DagGraphViewProps) {
  const [modalOpen, setModalOpen] = useState(false);

  return (
    <>
      {/* Interactive DAG in sidebar */}
      <div className="relative" style={{ height: 180 }}>
        <button
          onClick={() => setModalOpen(true)}
          className="absolute top-1.5 right-1.5 z-10 p-1 rounded bg-gray-800/80 border border-gray-700
            text-gray-400 hover:text-white hover:bg-gray-700 transition-colors"
          title="Fullscreen"
        >
          <Maximize2 size={12} />
        </button>
        <ReactFlowProvider>
          <DagGraphInner dag={dag} steps={steps} onOpenStep={onOpenStep} />
        </ReactFlowProvider>
      </div>

      {/* Fullscreen modal */}
      {modalOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
          onClick={() => setModalOpen(false)}
        >
          <div
            className="relative bg-[#1a1a2e] border border-gray-700 rounded-xl shadow-2xl
              w-[85vw] h-[75vh] max-w-[1200px] overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between px-4 py-2.5 border-b border-gray-700/60">
              <span className="text-sm font-medium text-gray-200">
                Expert Team DAG — {dag.length} steps
              </span>
              <button
                onClick={() => setModalOpen(false)}
                className="p-1.5 rounded-md text-gray-400 hover:text-gray-200 hover:bg-gray-700 transition-colors"
              >
                <Minimize2 size={16} />
              </button>
            </div>
            <div className="w-full" style={{ height: 'calc(100% - 44px)' }}>
              <ReactFlowProvider>
                <DagGraphInner dag={dag} steps={steps} onOpenStep={onOpenStep} isModal />
              </ReactFlowProvider>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
