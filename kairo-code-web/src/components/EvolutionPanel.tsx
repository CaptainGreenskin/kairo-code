import { useEffect, useState } from 'react';
import { Brain, CheckCircle, XCircle, Clock, Trash2, X, RefreshCw, Sparkles, BookOpen } from 'lucide-react';

interface Lesson {
    id: string;
    toolName: string;
    lessonText: string;
    status: 'PENDING' | 'APPROVED' | 'REJECTED';
    timestamp: string;
}

interface SkillEntry {
    name: string;
    description: string;
    priority: string;
    visibility: string;
}

interface EvolutionPanelProps {
    onClose: () => void;
}

type Tab = 'lessons' | 'skills';

export function EvolutionPanel({ onClose }: EvolutionPanelProps) {
    const [tab, setTab] = useState<Tab>('lessons');

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm" onClick={onClose}>
            <div
                className="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl shadow-2xl w-full max-w-2xl max-h-[80vh] flex flex-col overflow-hidden"
                onClick={e => e.stopPropagation()}
            >
                {/* Header */}
                <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--border)]">
                    <div className="flex items-center gap-2">
                        <Brain size={15} className="text-[var(--accent)]" />
                        <span className="text-sm font-semibold text-[var(--text-primary)]">Self-Evolution</span>
                    </div>
                    <button onClick={onClose}
                        className="p-1 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] hover:text-red-400 transition-colors">
                        <X size={13} />
                    </button>
                </div>

                {/* Tab bar */}
                <div className="flex border-b border-[var(--border)] bg-[var(--bg-primary)]">
                    <button
                        onClick={() => setTab('lessons')}
                        className={`flex items-center gap-1.5 px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
                            tab === 'lessons'
                                ? 'border-[var(--accent)] text-[var(--accent)]'
                                : 'border-transparent text-[var(--text-muted)] hover:text-[var(--text-primary)]'
                        }`}
                    >
                        <BookOpen size={12} />
                        Lessons
                    </button>
                    <button
                        onClick={() => setTab('skills')}
                        className={`flex items-center gap-1.5 px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
                            tab === 'skills'
                                ? 'border-[var(--accent)] text-[var(--accent)]'
                                : 'border-transparent text-[var(--text-muted)] hover:text-[var(--text-primary)]'
                        }`}
                    >
                        <Sparkles size={12} />
                        Skills
                    </button>
                </div>

                {tab === 'lessons' ? <LessonsTab /> : <SkillsTab />}
            </div>
        </div>
    );
}

function LessonsTab() {
    const [lessons, setLessons] = useState<Lesson[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [filter, setFilter] = useState<'ALL' | 'PENDING' | 'APPROVED' | 'REJECTED'>('ALL');

    const refresh = async () => {
        setLoading(true);
        setError(null);
        try {
            const res = await fetch('/api/evolution/lessons');
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            setLessons(await res.json());
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed to load lessons');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => { refresh(); }, []);

    const updateStatus = async (id: string, status: string) => {
        try {
            const res = await fetch(`/api/evolution/lessons/${id}/status`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ status }),
            });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            await refresh();
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed to update status');
        }
    };

    const deleteLesson = async (id: string) => {
        try {
            const res = await fetch(`/api/evolution/lessons/${id}`, { method: 'DELETE' });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            await refresh();
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed to delete lesson');
        }
    };

    const filtered = filter === 'ALL' ? lessons : lessons.filter(l => l.status === filter);
    const counts = {
        ALL: lessons.length,
        PENDING: lessons.filter(l => l.status === 'PENDING').length,
        APPROVED: lessons.filter(l => l.status === 'APPROVED').length,
        REJECTED: lessons.filter(l => l.status === 'REJECTED').length,
    };

    const statusIcon = (s: string) => {
        if (s === 'APPROVED') return <CheckCircle size={13} className="text-emerald-400 shrink-0" />;
        if (s === 'REJECTED') return <XCircle size={13} className="text-red-400 shrink-0" />;
        return <Clock size={13} className="text-yellow-400 shrink-0" />;
    };

    return (
        <>
            {/* Filter tabs + refresh */}
            <div className="flex items-center gap-1 px-4 py-2 border-b border-[var(--border)] bg-[var(--bg-primary)]">
                {(['ALL', 'PENDING', 'APPROVED', 'REJECTED'] as const).map(f => (
                    <button key={f} onClick={() => setFilter(f)}
                        className={`px-2.5 py-1 rounded text-xs font-medium transition-colors ${
                            filter === f
                                ? 'bg-[var(--accent)] text-white'
                                : 'text-[var(--text-muted)] hover:bg-[var(--bg-hover)]'
                        }`}>
                        {f} <span className="opacity-60">({counts[f]})</span>
                    </button>
                ))}
                <button onClick={refresh} disabled={loading}
                    className="ml-auto p-1 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] transition-colors">
                    <RefreshCw size={12} className={loading ? 'animate-spin' : ''} />
                </button>
            </div>

            {error && (
                <div className="px-4 py-2 text-xs text-red-400 bg-red-500/10 border-b border-[var(--border)]">
                    {error}
                </div>
            )}

            {/* Lessons list */}
            <div className="flex-1 overflow-y-auto divide-y divide-[var(--border)]">
                {filtered.length === 0 ? (
                    <div className="flex flex-col items-center justify-center py-12 text-[var(--text-muted)]">
                        <Brain size={32} className="opacity-30 mb-2" />
                        <p className="text-sm">No lessons yet.</p>
                        <p className="text-xs mt-1">Lessons are generated when tools fail 3+ times.</p>
                    </div>
                ) : filtered.map(lesson => (
                    <div key={lesson.id} className="px-4 py-3 hover:bg-[var(--bg-hover)] transition-colors">
                        <div className="flex items-start gap-2">
                            {statusIcon(lesson.status)}
                            <div className="flex-1 min-w-0">
                                <div className="flex items-center gap-2 mb-1">
                                    <span className="text-xs font-mono text-[var(--accent)] bg-[var(--bg-primary)] px-1.5 py-0.5 rounded">
                                        {lesson.toolName}
                                    </span>
                                    <span className="text-xs text-[var(--text-muted)]">
                                        {new Date(lesson.timestamp).toLocaleDateString()}
                                    </span>
                                </div>
                                <p className="text-xs text-[var(--text-primary)] leading-relaxed">{lesson.lessonText}</p>
                            </div>
                            <div className="flex items-center gap-1 shrink-0">
                                {lesson.status !== 'APPROVED' && (
                                    <button onClick={() => updateStatus(lesson.id, 'APPROVED')}
                                        className="p-1 rounded hover:bg-emerald-500/20 text-[var(--text-muted)] hover:text-emerald-400 transition-colors"
                                        title="Approve">
                                        <CheckCircle size={13} />
                                    </button>
                                )}
                                {lesson.status !== 'REJECTED' && (
                                    <button onClick={() => updateStatus(lesson.id, 'REJECTED')}
                                        className="p-1 rounded hover:bg-red-500/20 text-[var(--text-muted)] hover:text-red-400 transition-colors"
                                        title="Reject">
                                        <XCircle size={13} />
                                    </button>
                                )}
                                <button onClick={() => deleteLesson(lesson.id)}
                                    className="p-1 rounded hover:bg-red-500/20 text-[var(--text-muted)] hover:text-red-400 transition-colors"
                                    title="Delete">
                                    <Trash2 size={12} />
                                </button>
                            </div>
                        </div>
                    </div>
                ))}
            </div>
        </>
    );
}

function SkillsTab() {
    const [skills, setSkills] = useState<SkillEntry[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const refresh = async () => {
        setLoading(true);
        setError(null);
        try {
            const res = await fetch('/api/evolution/skills');
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            setSkills(await res.json());
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed to load skills');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => { refresh(); }, []);

    const deleteSkill = async (name: string) => {
        try {
            const res = await fetch(`/api/evolution/skills/${encodeURIComponent(name)}`, { method: 'DELETE' });
            if (!res.ok && res.status !== 404) throw new Error(`HTTP ${res.status}`);
            await refresh();
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed to delete skill');
        }
    };

    const priorityBadge = (p: string) => {
        const colors: Record<string, string> = {
            PROJECT: 'bg-blue-500/20 text-blue-400',
            MANAGED: 'bg-purple-500/20 text-purple-400',
            USER: 'bg-emerald-500/20 text-emerald-400',
            PLUGIN: 'bg-orange-500/20 text-orange-400',
        };
        return colors[p] ?? 'bg-gray-500/20 text-gray-400';
    };

    return (
        <>
            {/* Header with refresh */}
            <div className="flex items-center px-4 py-2 border-b border-[var(--border)] bg-[var(--bg-primary)]">
                <span className="text-xs text-[var(--text-muted)]">{skills.length} skills loaded</span>
                <button onClick={refresh} disabled={loading}
                    className="ml-auto p-1 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] transition-colors">
                    <RefreshCw size={12} className={loading ? 'animate-spin' : ''} />
                </button>
            </div>

            {error && (
                <div className="px-4 py-2 text-xs text-red-400 bg-red-500/10 border-b border-[var(--border)]">
                    {error}
                </div>
            )}

            {/* Skills list */}
            <div className="flex-1 overflow-y-auto divide-y divide-[var(--border)]">
                {skills.length === 0 ? (
                    <div className="flex flex-col items-center justify-center py-12 text-[var(--text-muted)]">
                        <Sparkles size={32} className="opacity-30 mb-2" />
                        <p className="text-sm">No skills found.</p>
                        <p className="text-xs mt-1">Skills are generated by the self-evolution pipeline.</p>
                    </div>
                ) : skills.map(skill => (
                    <div key={skill.name} className="px-4 py-3 hover:bg-[var(--bg-hover)] transition-colors">
                        <div className="flex items-start gap-2">
                            <Sparkles size={13} className="text-[var(--accent)] shrink-0 mt-0.5" />
                            <div className="flex-1 min-w-0">
                                <div className="flex items-center gap-2 mb-1">
                                    <span className="text-xs font-semibold text-[var(--text-primary)]">
                                        {skill.name}
                                    </span>
                                    <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${priorityBadge(skill.priority)}`}>
                                        {skill.priority}
                                    </span>
                                    {skill.visibility !== 'VISIBLE' && (
                                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-yellow-500/20 text-yellow-400 font-medium">
                                            {skill.visibility}
                                        </span>
                                    )}
                                </div>
                                {skill.description && (
                                    <p className="text-xs text-[var(--text-secondary)] leading-relaxed truncate">
                                        {skill.description}
                                    </p>
                                )}
                            </div>
                            {skill.priority === 'MANAGED' && (
                                <button onClick={() => deleteSkill(skill.name)}
                                    className="p-1 rounded hover:bg-red-500/20 text-[var(--text-muted)] hover:text-red-400 transition-colors shrink-0"
                                    title="Delete managed skill">
                                    <Trash2 size={12} />
                                </button>
                            )}
                        </div>
                    </div>
                ))}
            </div>
        </>
    );
}
