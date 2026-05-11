import { useState, useEffect, useCallback } from 'react';
import { X, GitBranch, RefreshCw, Plus, Minus, ChevronRight, ChevronDown, Check, RotateCcw, History } from 'lucide-react';
import { useWorkspaceStore } from '@store/workspaceStore';

interface GitFile {
    status: string;
    path: string;
}

interface GitCommit {
    sha: string;
    shortSha: string;
    subject: string;
    author: string;
    relDate: string;
}

interface BranchInfo {
    name: string;
    detachedSha: string;
}

type Tab = 'changes' | 'history';

interface GitStatusPanelProps {
    onClose: () => void;
    /** When true, render inline (no fixed/modal backdrop or border-radius). */
    embedded?: boolean;
}

const STATUS_LABELS: Record<string, { label: string; color: string }> = {
    M: { label: 'M', color: 'text-amber-400' },
    A: { label: 'A', color: 'text-emerald-400' },
    D: { label: 'D', color: 'text-red-400' },
    '??': { label: '?', color: 'text-[var(--text-muted)]' },
};

function getStatusStyle(status: string) {
    return STATUS_LABELS[status] ?? { label: status, color: 'text-[var(--text-muted)]' };
}

function DiffLine({ line }: { line: string }) {
    if (line.startsWith('+++') || line.startsWith('---')) {
        return <div className="text-[var(--text-muted)] text-[10px] font-mono px-2 py-0.5">{line}</div>;
    }
    if (line.startsWith('+')) {
        return (
            <div className="bg-emerald-500/10 text-emerald-400 text-[10px] font-mono px-2 py-0.5 flex items-start gap-1">
                <Plus size={9} className="mt-0.5 shrink-0" />
                <span className="whitespace-pre-wrap break-all">{line.slice(1)}</span>
            </div>
        );
    }
    if (line.startsWith('-')) {
        return (
            <div className="bg-red-500/10 text-red-400 text-[10px] font-mono px-2 py-0.5 flex items-start gap-1">
                <Minus size={9} className="mt-0.5 shrink-0" />
                <span className="whitespace-pre-wrap break-all">{line.slice(1)}</span>
            </div>
        );
    }
    if (line.startsWith('@@')) {
        return <div className="bg-blue-500/10 text-blue-400 text-[10px] font-mono px-2 py-1">{line}</div>;
    }
    return <div className="text-[var(--text-secondary)] text-[10px] font-mono px-2 py-0.5 whitespace-pre-wrap break-all">{line}</div>;
}

function FileDiff({ path, workspaceId, untracked = false }: { path: string; workspaceId: string | null; untracked?: boolean }) {
    const [diff, setDiff] = useState<string | null>(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        const qs = new URLSearchParams({ path });
        if (workspaceId) qs.set('workspaceId', workspaceId);
        if (untracked) qs.set('untracked', 'true');
        fetch(`/api/git/diff?${qs.toString()}`)
            .then(async (res) => {
                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                const data = (await res.json()) as { diff: string };
                if (!cancelled) setDiff(data.diff || '(no changes)');
            })
            .catch(() => {
                if (!cancelled) setDiff('(failed to load diff)');
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, [path, workspaceId]);

    if (loading) {
        return (
            <div className="px-3 py-2 text-[10px] text-[var(--text-muted)] border-t border-[var(--border)]">
                Loading diff…
            </div>
        );
    }
    if (!diff) return null;
    return (
        <div className="border-t border-[var(--border)] overflow-x-auto max-h-64 overflow-y-auto bg-[var(--bg-primary)]">
            {diff.split('\n').map((line, i) => (
                <DiffLine key={i} line={line} />
            ))}
        </div>
    );
}

export function GitStatusPanel({ onClose, embedded = false }: GitStatusPanelProps) {
    const currentWorkspaceId = useWorkspaceStore((s) => s.currentWorkspaceId);
    const [files, setFiles] = useState<GitFile[]>([]);
    const [branch, setBranch] = useState<BranchInfo | null>(null);
    const [commits, setCommits] = useState<GitCommit[]>([]);
    const [tab, setTab] = useState<Tab>('changes');
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [expandedFiles, setExpandedFiles] = useState<Set<string>>(new Set());
    const [commitMsg, setCommitMsg] = useState('');
    const [committing, setCommitting] = useState(false);
    const [commitNotice, setCommitNotice] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);
    const [restoringPath, setRestoringPath] = useState<string | null>(null);

    const buildQs = useCallback(
        (extra?: Record<string, string>) => {
            const params = new URLSearchParams(extra);
            if (currentWorkspaceId) params.set('workspaceId', currentWorkspaceId);
            const s = params.toString();
            return s ? `?${s}` : '';
        },
        [currentWorkspaceId],
    );

    const refresh = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            const qs = buildQs();
            // Hit status + branch in parallel; log only fetched on tab activation.
            const [statusRes, branchRes] = await Promise.all([
                fetch(`/api/git/status${qs}`),
                fetch(`/api/git/branch${qs}`),
            ]);
            if (!statusRes.ok) throw new Error(`HTTP ${statusRes.status}`);
            const data = (await statusRes.json()) as GitFile[];
            setFiles(data);
            if (branchRes.ok) {
                setBranch((await branchRes.json()) as BranchInfo);
            } else {
                setBranch(null);
            }
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed to load git status');
        }
        setLoading(false);
    }, [buildQs]);

    useEffect(() => {
        refresh();
    }, [refresh]);

    const loadHistory = useCallback(async () => {
        try {
            const res = await fetch(`/api/git/log${buildQs({ limit: '20' })}`);
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            setCommits((await res.json()) as GitCommit[]);
        } catch {
            setCommits([]);
        }
    }, [buildQs]);

    useEffect(() => {
        if (tab === 'history') loadHistory();
    }, [tab, loadHistory]);

    const handleDiscard = async (file: GitFile) => {
        const ok = window.confirm(
            `Discard local changes for ${file.path}?\nThis cannot be undone.`,
        );
        if (!ok) return;
        setRestoringPath(file.path);
        try {
            const body = file.status === '??'
                ? { untracked: [file.path] }
                : { paths: [file.path] };
            const res = await fetch(`/api/git/restore${buildQs()}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body),
            });
            if (!res.ok) throw new Error(await res.text() || `HTTP ${res.status}`);
            await refresh();
        } catch (e) {
            setCommitNotice({ kind: 'err', text: e instanceof Error ? e.message : 'Discard failed' });
        } finally {
            setRestoringPath(null);
        }
    };

    const handleCommit = async () => {
        if (!commitMsg.trim() || committing || files.length === 0) return;
        setCommitting(true);
        setCommitNotice(null);
        try {
            const qs = buildQs();
            const res = await fetch(`/api/git/commit${qs}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ message: commitMsg.trim(), stageAll: true }),
            });
            if (!res.ok) {
                const txt = await res.text();
                throw new Error(txt || `HTTP ${res.status}`);
            }
            setCommitMsg('');
            setCommitNotice({ kind: 'ok', text: 'Committed' });
            await refresh();
        } catch (e) {
            setCommitNotice({ kind: 'err', text: e instanceof Error ? e.message : 'Commit failed' });
        } finally {
            setCommitting(false);
        }
    };

    const inner = (
        <>
            <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--border)] bg-[var(--bg-secondary)] shrink-0">
                <div className="flex items-center gap-2 min-w-0">
                        <GitBranch size={15} className="text-[var(--accent)] shrink-0" />
                        <span className="text-sm font-semibold text-[var(--text-primary)]">Git</span>
                        {branch && (branch.name || branch.detachedSha) && (
                            <span
                                className="text-xs font-mono px-1.5 py-0.5 rounded bg-[var(--bg-hover)] text-[var(--text-secondary)] truncate max-w-[160px]"
                                title={branch.name ? `Branch: ${branch.name}` : `Detached @ ${branch.detachedSha}`}
                            >
                                {branch.name || `(detached) ${branch.detachedSha}`}
                            </span>
                        )}
                        {tab === 'changes' && files.length > 0 && (
                            <span className="text-xs text-[var(--text-muted)]">
                                {files.length} file{files.length !== 1 ? 's' : ''}
                            </span>
                        )}
                    </div>
                    <div className="flex items-center gap-1.5">
                        <button
                            onClick={refresh}
                            disabled={loading}
                            className="p-1.5 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                            title="Refresh"
                            aria-label="Refresh git status"
                        >
                            <RefreshCw size={13} className={loading ? 'animate-spin' : ''} />
                        </button>
                        <button
                            onClick={onClose}
                            className="p-1.5 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                            aria-label="Close"
                        >
                            <X size={16} />
                        </button>
                    </div>
                </div>

                {/* Tabs */}
                <div className="flex items-center gap-1 px-3 py-1.5 border-b border-[var(--border)] bg-[var(--bg-secondary)] shrink-0">
                    {(['changes', 'history'] as Tab[]).map((t) => (
                        <button
                            key={t}
                            onClick={() => setTab(t)}
                            className={`flex items-center gap-1.5 px-3 py-1 rounded text-xs font-medium transition-colors ${
                                tab === t
                                    ? 'bg-[var(--accent)]/15 text-[var(--accent)]'
                                    : 'text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)]'
                            }`}
                        >
                            {t === 'changes' ? <GitBranch size={12} /> : <History size={12} />}
                            {t === 'changes' ? 'Changes' : 'History'}
                        </button>
                    ))}
                </div>

                <div className="flex-1 overflow-y-auto">
                    {error ? (
                        <div className="flex flex-col items-center justify-center h-full text-sm px-4 text-center gap-2">
                            <span className={error.includes('503')
                                ? 'text-[var(--text-muted)]'
                                : 'text-red-400'}>
                                {error.includes('503')
                                    ? 'Configure working directory in Settings'
                                    : error.includes('500') || error.includes('128') || error.toLowerCase().includes('not a git')
                                        ? 'Not a git repository or git not available'
                                        : error}
                            </span>
                        </div>
                    ) : tab === 'history' ? (
                        commits.length === 0 ? (
                            <div className="flex items-center justify-center h-full text-sm text-[var(--text-muted)]">
                                No commits yet
                            </div>
                        ) : (
                            <div>
                                {commits.map((c) => (
                                    <div
                                        key={c.sha}
                                        className="px-4 py-2 border-b border-[var(--border)] last:border-0 hover:bg-[var(--bg-hover)] transition-colors"
                                        title={c.sha}
                                    >
                                        <div className="flex items-baseline gap-2">
                                            <span className="text-[10px] font-mono text-[var(--accent)] shrink-0">
                                                {c.shortSha}
                                            </span>
                                            <span className="text-xs text-[var(--text-primary)] truncate flex-1">
                                                {c.subject}
                                            </span>
                                        </div>
                                        <div className="text-[10px] text-[var(--text-muted)] mt-0.5 truncate">
                                            {c.author} · {c.relDate}
                                        </div>
                                    </div>
                                ))}
                            </div>
                        )
                    ) : loading && files.length === 0 ? (
                        <div className="flex items-center justify-center h-full text-sm text-[var(--text-muted)]">
                            Loading…
                        </div>
                    ) : files.length === 0 ? (
                        <div className="flex flex-col items-center justify-center h-full text-sm text-[var(--text-muted)] gap-2">
                            <GitBranch size={32} className="opacity-30" />
                            <span>Working tree is clean</span>
                        </div>
                    ) : (
                        <div>
                            {files.map((file) => {
                                const s = getStatusStyle(file.status);
                                const isExpanded = expandedFiles.has(file.path);
                                const isRestoring = restoringPath === file.path;
                                return (
                                    <div key={file.path} className="border-b border-[var(--border)] last:border-0">
                                        <div className="w-full flex items-center gap-2 px-4 py-2.5 hover:bg-[var(--bg-hover)] transition-colors group">
                                            <button
                                                onClick={() => setExpandedFiles((prev) => {
                                                    const next = new Set(prev);
                                                    if (next.has(file.path)) next.delete(file.path);
                                                    else next.add(file.path);
                                                    return next;
                                                })}
                                                className="flex items-center gap-2 flex-1 min-w-0 text-left"
                                            >
                                                <span className={`text-xs font-mono font-bold w-4 shrink-0 ${s.color}`}>
                                                    {s.label}
                                                </span>
                                                <span className="text-xs text-[var(--text-primary)] font-mono truncate flex-1">
                                                    {file.path}
                                                </span>
                                                {isExpanded ? (
                                                    <ChevronDown size={12} className="text-[var(--text-muted)] shrink-0" />
                                                ) : (
                                                    <ChevronRight size={12} className="text-[var(--text-muted)] shrink-0" />
                                                )}
                                            </button>
                                            <button
                                                onClick={(e) => { e.stopPropagation(); handleDiscard(file); }}
                                                disabled={isRestoring}
                                                title={file.status === '??' ? 'Delete untracked file' : 'Discard changes (restore from HEAD)'}
                                                className="opacity-0 group-hover:opacity-100 p-1 rounded hover:bg-[var(--bg-primary)] text-[var(--text-muted)] hover:text-red-400 transition-all disabled:opacity-40 shrink-0"
                                            >
                                                <RotateCcw size={12} className={isRestoring ? 'animate-spin' : ''} />
                                            </button>
                                        </div>
                                        {isExpanded && file.path.endsWith('/') ? (
                                            <div className="px-4 py-2 text-[10px] text-[var(--text-muted)] border-t border-[var(--border)]">
                                                Untracked directory — expand individual files to view diff
                                            </div>
                                        ) : isExpanded ? (
                                            <FileDiff
                                                path={file.path}
                                                workspaceId={currentWorkspaceId}
                                                untracked={file.status === '??'}
                                            />
                                        ) : null}
                                    </div>
                                );
                            })}
                        </div>
                    )}
                </div>

                {tab === 'changes' && files.length > 0 && (
                    <div className="px-4 py-2.5 border-t border-[var(--border)] bg-[var(--bg-secondary)] shrink-0 space-y-2">
                        <div className="flex items-start gap-2">
                            <textarea
                                value={commitMsg}
                                onChange={(e) => setCommitMsg(e.target.value)}
                                onKeyDown={(e) => {
                                    if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
                                        e.preventDefault();
                                        handleCommit();
                                    }
                                }}
                                placeholder="Commit message (⌘↵ to commit)…"
                                rows={2}
                                disabled={committing}
                                className="flex-1 text-xs px-2 py-1.5 rounded bg-[var(--bg-primary)] border border-[var(--border)] text-[var(--text-primary)] placeholder:text-[var(--text-muted)] resize-none focus:outline-none focus:border-[var(--accent)] disabled:opacity-50"
                            />
                            <button
                                onClick={handleCommit}
                                disabled={!commitMsg.trim() || committing}
                                title="Stage all & commit"
                                className="flex items-center gap-1 px-3 py-1.5 text-xs font-medium rounded bg-[var(--color-primary,#10b981)] text-white hover:opacity-90 transition-opacity disabled:opacity-40 disabled:cursor-not-allowed shrink-0"
                            >
                                <Check size={13} />
                                {committing ? 'Committing…' : 'Commit'}
                            </button>
                        </div>
                        {commitNotice && (
                            <div className={`text-[10px] ${commitNotice.kind === 'ok' ? 'text-emerald-400' : 'text-red-400'}`}>
                                {commitNotice.text}
                            </div>
                        )}
                    </div>
                )}

            <div className="px-4 py-2 border-t border-[var(--border)] bg-[var(--bg-secondary)] shrink-0 text-[10px] text-[var(--text-muted)]">
                M modified · A added · D deleted · ? untracked · click file to expand diff
            </div>
        </>
    );

    if (embedded) {
        return (
            <div className="flex flex-col h-full bg-[var(--bg-primary)] overflow-hidden">
                {inner}
            </div>
        );
    }

    return (
        <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
            onClick={(e) => {
                if (e.target === e.currentTarget) onClose();
            }}
        >
            <div className="relative flex flex-col w-full max-w-2xl h-[75vh] max-h-[750px] bg-[var(--bg-primary)] border border-[var(--border)] rounded-xl shadow-2xl overflow-hidden">
                {inner}
            </div>
        </div>
    );
}
