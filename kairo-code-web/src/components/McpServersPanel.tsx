import { useState, useEffect, useCallback } from 'react';
import { X, Plus, Trash2, Power, PowerOff, Settings2 } from 'lucide-react';

interface McpServer {
    name: string;
    command: string;
    args: string[];
    env: Record<string, string>;
    disabled: boolean;
}

interface McpServersPanelProps {
    onClose: () => void;
}

export function McpServersPanel({ onClose }: McpServersPanelProps) {
    const [servers, setServers] = useState<McpServer[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [showAdd, setShowAdd] = useState(false);

    const [formName, setFormName] = useState('');
    const [formCmd, setFormCmd] = useState('');
    const [formArgs, setFormArgs] = useState('');
    const [formEnv, setFormEnv] = useState('');

    const refresh = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            const res = await fetch('/api/mcp/servers');
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            setServers(await res.json());
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed to load MCP servers');
        }
        setLoading(false);
    }, []);

    useEffect(() => { refresh(); }, [refresh]);

    useEffect(() => {
        const handleEsc = (e: KeyboardEvent) => {
            if (e.key === 'Escape') onClose();
        };
        window.addEventListener('keydown', handleEsc);
        return () => window.removeEventListener('keydown', handleEsc);
    }, [onClose]);

    const parseEnv = (raw: string): Record<string, string> =>
        Object.fromEntries(
            raw.split('\n').map(l => l.trim()).filter(Boolean)
                .map(l => {
                    const idx = l.indexOf('=');
                    if (idx < 0) return ['', ''];
                    return [l.slice(0, idx).trim(), l.slice(idx + 1)];
                })
                .filter(([k]) => k),
        );

    const resetForm = () => {
        setFormName('');
        setFormCmd('');
        setFormArgs('');
        setFormEnv('');
    };

    const handleAdd = async () => {
        if (!formName.trim() || !formCmd.trim()) return;
        const server: McpServer = {
            name: formName.trim(),
            command: formCmd.trim(),
            args: formArgs.trim() ? formArgs.trim().split(/\s+/) : [],
            env: parseEnv(formEnv),
            disabled: false,
        };
        try {
            const res = await fetch('/api/mcp/servers', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(server),
            });
            if (res.ok) {
                setShowAdd(false);
                resetForm();
                refresh();
            } else {
                const text = await res.text().catch(() => '');
                setError(`Add failed (${res.status})${text ? ': ' + text : ''}`);
            }
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Add failed');
        }
    };

    const handleToggle = async (name: string) => {
        try {
            const res = await fetch(`/api/mcp/servers/${name}/toggle`, { method: 'POST' });
            if (!res.ok) {
                setError(`Toggle failed (${res.status})`);
                return;
            }
            refresh();
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Toggle failed');
        }
    };

    const handleDelete = async (name: string) => {
        if (!confirm(`Delete MCP server "${name}"?`)) return;
        try {
            const res = await fetch(`/api/mcp/servers/${name}`, { method: 'DELETE' });
            if (!res.ok && res.status !== 404) {
                setError(`Delete failed (${res.status})`);
                return;
            }
            refresh();
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Delete failed');
        }
    };

    return (
        <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
            onClick={e => { if (e.target === e.currentTarget) onClose(); }}
        >
            <div className="relative flex flex-col w-full max-w-2xl h-[75vh] max-h-[700px] mx-4
                bg-[var(--bg-primary)] border border-[var(--border)] rounded-xl shadow-2xl overflow-hidden">

                {/* Header */}
                <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--border)] bg-[var(--bg-secondary)] shrink-0">
                    <div className="flex items-center gap-2">
                        <Settings2 size={15} className="text-[var(--color-primary)]" />
                        <span className="text-sm font-semibold text-[var(--text-primary)]">MCP Servers</span>
                        <span className="text-xs text-[var(--text-muted)]">{servers.length} configured</span>
                    </div>
                    <div className="flex items-center gap-2">
                        <button
                            onClick={() => setShowAdd(true)}
                            className="flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-medium
                                bg-[var(--color-primary)] text-white hover:bg-[var(--color-primary-hover)] transition-colors"
                        >
                            <Plus size={12} /> Add Server
                        </button>
                        <button
                            onClick={onClose}
                            className="p-1.5 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                            aria-label="Close MCP servers"
                        >
                            <X size={16} />
                        </button>
                    </div>
                </div>

                {/* Add form */}
                {showAdd && (
                    <div className="px-4 py-3 border-b border-[var(--border)] bg-[var(--bg-secondary)]/50 space-y-2 shrink-0">
                        <div className="grid grid-cols-2 gap-2">
                            <input autoFocus placeholder="Server name" value={formName}
                                onChange={e => setFormName(e.target.value)} maxLength={64}
                                className="px-3 py-1.5 rounded bg-[var(--bg-primary)] border border-[var(--border)]
                                    text-sm text-[var(--text-primary)] outline-none focus:border-[var(--color-primary)]" />
                            <input placeholder="Command (e.g. node, python)" value={formCmd}
                                onChange={e => setFormCmd(e.target.value)}
                                className="px-3 py-1.5 rounded bg-[var(--bg-primary)] border border-[var(--border)]
                                    text-sm text-[var(--text-primary)] outline-none focus:border-[var(--color-primary)]" />
                        </div>
                        <input
                            placeholder="Arguments (space-separated, e.g. /path/to/server.js --port 8080)"
                            value={formArgs}
                            onChange={e => setFormArgs(e.target.value)}
                            className="w-full px-3 py-1.5 rounded bg-[var(--bg-primary)] border border-[var(--border)]
                                text-sm text-[var(--text-primary)] font-mono outline-none focus:border-[var(--color-primary)]" />
                        <textarea
                            placeholder={'Environment variables (one per line, KEY=VALUE)'}
                            value={formEnv}
                            onChange={e => setFormEnv(e.target.value)}
                            rows={2}
                            className="w-full px-3 py-1.5 rounded bg-[var(--bg-primary)] border border-[var(--border)]
                                text-sm text-[var(--text-primary)] font-mono outline-none focus:border-[var(--color-primary)] resize-none" />
                        <div className="flex gap-2 justify-end">
                            <button
                                onClick={() => { setShowAdd(false); resetForm(); }}
                                className="px-3 py-1.5 rounded text-xs text-[var(--text-muted)] hover:bg-[var(--bg-hover)] transition-colors"
                            >
                                Cancel
                            </button>
                            <button
                                onClick={handleAdd}
                                disabled={!formName.trim() || !formCmd.trim()}
                                className="px-3 py-1.5 rounded text-xs font-medium bg-[var(--color-primary)] text-white
                                    hover:bg-[var(--color-primary-hover)] transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                            >
                                Add
                            </button>
                        </div>
                    </div>
                )}

                {/* Error */}
                {error && (
                    <div className="px-4 py-2 bg-red-500/10 text-xs text-red-400 border-b border-red-500/20 shrink-0">
                        {error}
                    </div>
                )}

                {/* Server list */}
                <div className="flex-1 overflow-y-auto">
                    {loading && servers.length === 0 ? (
                        <div className="flex items-center justify-center h-full text-sm text-[var(--text-muted)]">Loading…</div>
                    ) : servers.length === 0 ? (
                        <div className="flex flex-col items-center justify-center h-full text-sm text-[var(--text-muted)] gap-2 px-6 text-center">
                            <Settings2 size={32} className="opacity-30" />
                            <span>No MCP servers configured. Click <strong>Add Server</strong> to get started.</span>
                        </div>
                    ) : (
                        <div className="divide-y divide-[var(--border)]">
                            {servers.map(server => (
                                <div
                                    key={server.name}
                                    className={`flex items-start gap-3 px-4 py-3 ${server.disabled ? 'opacity-50' : ''}`}
                                >
                                    <div className="flex-1 min-w-0">
                                        <div className="flex items-center gap-2">
                                            <span className="text-sm font-mono font-semibold text-[var(--text-primary)]">{server.name}</span>
                                            {server.disabled && (
                                                <span className="text-[10px] px-1.5 py-0.5 rounded bg-[var(--bg-hover)] text-[var(--text-muted)]">
                                                    disabled
                                                </span>
                                            )}
                                        </div>
                                        <div className="text-xs text-[var(--text-muted)] font-mono mt-0.5 truncate">
                                            {server.command} {server.args.join(' ')}
                                        </div>
                                        {Object.keys(server.env).length > 0 && (
                                            <div className="text-[10px] text-[var(--text-muted)] mt-0.5">
                                                env: {Object.keys(server.env).join(', ')}
                                            </div>
                                        )}
                                    </div>
                                    <div className="flex items-center gap-1 shrink-0">
                                        <button
                                            onClick={() => handleToggle(server.name)}
                                            className={`p-1.5 rounded transition-colors ${
                                                server.disabled
                                                    ? 'text-[var(--text-muted)] hover:text-emerald-400 hover:bg-emerald-500/10'
                                                    : 'text-emerald-400 hover:text-[var(--text-muted)] hover:bg-[var(--bg-hover)]'
                                            }`}
                                            title={server.disabled ? 'Enable' : 'Disable'}
                                            aria-label={server.disabled ? `Enable ${server.name}` : `Disable ${server.name}`}
                                        >
                                            {server.disabled ? <Power size={14} /> : <PowerOff size={14} />}
                                        </button>
                                        <button
                                            onClick={() => handleDelete(server.name)}
                                            className="p-1.5 rounded hover:bg-red-500/10 text-[var(--text-muted)] hover:text-red-400 transition-colors"
                                            title="Delete"
                                            aria-label={`Delete ${server.name}`}
                                        >
                                            <Trash2 size={14} />
                                        </button>
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}
                </div>

                {/* Footer */}
                <div className="px-4 py-2 border-t border-[var(--border)] bg-[var(--bg-secondary)] shrink-0 text-[10px] text-[var(--text-muted)]">
                    Stored in {'{workingDir}'}/.mcp.json · Restart agent session to apply changes
                </div>
            </div>
        </div>
    );
}
