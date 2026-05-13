export interface ExpertTooltipData {
  roleId: string;
  roleName: string;
  description?: string;
  skills?: string[];
  instruction?: string;
  status: string;
  startedAt?: string;
  completedAt?: string;
}

interface ExpertTooltipProps {
  data: ExpertTooltipData;
  position: { x: number; y: number };
  visible: boolean;
}

const ROLE_DESCRIPTIONS: Record<string, { description: string; skills: string[] }> = {
  architect: {
    description: 'Designs system architecture and decomposes work into steps',
    skills: ['System Design', 'Task Decomposition', 'Dependency Analysis'],
  },
  researcher: {
    description: 'Gathers context by reading code, searching, and analyzing',
    skills: ['Code Search', 'File Reading', 'Pattern Analysis'],
  },
  coder: {
    description: 'Implements code changes based on instructions',
    skills: ['Code Generation', 'File Editing', 'Refactoring'],
  },
  reviewer: {
    description: 'Reviews code quality, correctness, and adherence to standards',
    skills: ['Code Review', 'Static Analysis', 'Best Practices'],
  },
  tester: {
    description: 'Writes and runs tests to verify correctness',
    skills: ['Test Writing', 'Test Execution', 'Coverage Analysis'],
  },
  synthesizer: {
    description: 'Synthesizes final output from all expert contributions',
    skills: ['Summarization', 'Report Generation', 'Integration'],
  },
};

function getTimingInfo(_status: string, startedAt?: string, completedAt?: string): string | null {
  if (!startedAt) return null;
  const start = new Date(startedAt).getTime();
  const end = completedAt ? new Date(completedAt).getTime() : Date.now();
  const elapsed = Math.round((end - start) / 1000);
  if (elapsed < 60) return `${elapsed}s`;
  const mins = Math.floor(elapsed / 60);
  const secs = elapsed % 60;
  return `${mins}m ${secs}s`;
}

export function ExpertTooltip({ data, position, visible }: ExpertTooltipProps) {
  if (!visible) return null;

  const roleInfo = ROLE_DESCRIPTIONS[data.roleId.toLowerCase()];
  const description = data.description || roleInfo?.description || 'Expert agent';
  const skills = data.skills ?? roleInfo?.skills ?? [];
  const timing = getTimingInfo(data.status, data.startedAt, data.completedAt);

  return (
    <div
      className="absolute z-50 pointer-events-none"
      style={{ left: position.x, top: position.y }}
    >
      <div className="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-lg shadow-xl
                      p-3 min-w-[220px] max-w-[300px] text-left">
        {/* Role name */}
        <div className="flex items-center gap-2 mb-1.5">
          <span className="text-sm font-semibold text-[var(--text-primary)]">{data.roleName}</span>
          <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded-full capitalize
            ${data.status === 'done' ? 'bg-green-500/20 text-green-400' :
              data.status === 'working' ? 'bg-amber-500/20 text-amber-400' :
              data.status === 'thinking' ? 'bg-blue-400/20 text-blue-300' :
              data.status === 'failed' ? 'bg-red-500/20 text-red-400' :
              'bg-gray-500/20 text-gray-400'}`}>
            {data.status}
            {timing && ` · ${timing}`}
          </span>
        </div>

        {/* Description */}
        <p className="text-[11px] text-[var(--text-secondary)] mb-2">{description}</p>

        {/* Skills */}
        {skills.length > 0 && (
          <div className="mb-2">
            <span className="text-[10px] font-medium text-[var(--text-muted)] uppercase tracking-wide">
              Skills
            </span>
            <div className="flex flex-wrap gap-1 mt-0.5">
              {skills.map((skill) => (
                <span
                  key={skill}
                  className="text-[10px] px-1.5 py-0.5 rounded bg-[var(--bg-primary)]
                             text-[var(--text-secondary)] border border-[var(--border)]"
                >
                  {skill}
                </span>
              ))}
            </div>
          </div>
        )}

        {/* Current instruction */}
        {data.instruction && (
          <div>
            <span className="text-[10px] font-medium text-[var(--text-muted)] uppercase tracking-wide">
              Instruction
            </span>
            <p className="text-[10px] text-[var(--text-secondary)] mt-0.5 line-clamp-3">
              {data.instruction}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
