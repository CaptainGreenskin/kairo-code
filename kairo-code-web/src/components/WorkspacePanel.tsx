import { useEffect, useState } from 'react';

interface WorkspaceSummary {
    sessionId: string;
    workingDir: string;
    status: 'running' | 'idle';
    lastActivity: number;
    messageCount: number;
}

interface WorkspacePanelProps {
    currentSessionId: string | null;
    onSelectSession: (sessionId: string) => void;
}

export function WorkspacePanel({ currentSessionId, onSelectSession }: WorkspacePanelProps) {
    const [workspaces, setWorkspaces] = useState<WorkspaceSummary[]>([]);

    useEffect(() => {
        const load = () =>
            fetch('/api/workspaces')
                .then(r => r.json())
                .then(setWorkspaces)
                .catch(() => {});
        load();
        const id = setInterval(load, 5000);
        return () => clearInterval(id);
    }, []);

    if (workspaces.length === 0) return null;

    return (
        <div className="border-b border-[var(--border)] p-2">
            <div className="text-xs text-[var(--text-muted)] mb-1 px-1 font-medium">Workspaces</div>
            <div className="flex flex-col gap-1">
                {workspaces.map(ws => (
                    <button
                        key={ws.sessionId}
                        onClick={() => onSelectSession(ws.sessionId)}
                        className={`
                            flex items-center gap-2 px-2 py-1.5 rounded text-xs text-left
                            hover:bg-[var(--bg-hover)] transition-colors
                            ${ws.sessionId === currentSessionId ? 'bg-[var(--color-primary)]/10' : ''}
                        `}
                    >
                        <span className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${
                            ws.status === 'running' ? 'bg-green-400 animate-pulse' :
                                                      'bg-[var(--text-muted)]'
                        }`} />
                        <span className="truncate text-[var(--text-secondary)]" title={ws.workingDir}>
                            {ws.workingDir.split('/').pop() || ws.workingDir}
                        </span>
                        {ws.messageCount > 0 && (
                            <span className="ml-auto text-[var(--text-muted)] flex-shrink-0">
                                {ws.messageCount}
                            </span>
                        )}
                    </button>
                ))}
            </div>
        </div>
    );
}
