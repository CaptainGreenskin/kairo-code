import { useState, useEffect, useCallback } from 'react';
import { X, Sparkles, Download, Trash2, RefreshCw, Search, Play, Square, ChevronDown, ChevronRight } from 'lucide-react';

interface Skill {
    name: string;
    description: string;
    category: string;
    priority: string;
    visibility: string;
    triggers: string[];
    version: string;
    hasInstructions: boolean;
    loaded?: boolean;
}

interface ManagedSkill {
    name: string;
    gitUrl: string;
    path: string;
}

type Tab = 'all' | 'managed';

const CATEGORY_COLORS: Record<string, string> = {
    CODE: '#6366f1',
    DEVOPS: '#f59e0b',
    TESTING: '#10b981',
    DOCUMENTATION: '#3b82f6',
    DATA: '#ec4899',
    GENERAL: '#6b7280',
};

export function SkillsPanel({ onClose }: { onClose: () => void }) {
    const [tab, setTab] = useState<Tab>('all');
    const [skills, setSkills] = useState<Skill[]>([]);
    const [managed, setManaged] = useState<ManagedSkill[]>([]);
    const [loadedSkills, setLoadedSkills] = useState<Set<string>>(new Set());
    const [loading, setLoading] = useState(true);
    const [search, setSearch] = useState('');
    const [expandedSkill, setExpandedSkill] = useState<string | null>(null);
    const [skillDetail, setSkillDetail] = useState<string>('');
    const [showInstall, setShowInstall] = useState(false);
    const [installSource, setInstallSource] = useState('');
    const [installError, setInstallError] = useState('');
    const [installing, setInstalling] = useState(false);

    const fetchSkills = useCallback(async () => {
        try {
            const [skillsRes, managedRes, loadedRes] = await Promise.all([
                fetch('/api/skills'),
                fetch('/api/skills/managed'),
                fetch('/api/skills/loaded'),
            ]);
            if (skillsRes.ok) setSkills(await skillsRes.json());
            if (managedRes.ok) setManaged(await managedRes.json());
            if (loadedRes.ok) {
                const names: string[] = await loadedRes.json();
                setLoadedSkills(new Set(names));
            }
        } catch { /* ignore */ }
        setLoading(false);
    }, []);

    useEffect(() => { fetchSkills(); }, [fetchSkills]);

    useEffect(() => {
        const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [onClose]);

    const filtered = skills.filter(s => {
        const q = search.toLowerCase();
        return !q || s.name.toLowerCase().includes(q)
            || s.description.toLowerCase().includes(q)
            || s.triggers.some(t => t.toLowerCase().includes(q));
    });

    const handleLoad = async (name: string) => {
        try {
            const res = await fetch(`/api/skills/${encodeURIComponent(name)}/load`, { method: 'POST' });
            if (res.ok) {
                setLoadedSkills(prev => new Set(prev).add(name));
            }
        } catch { /* ignore */ }
    };

    const handleUnload = async (name: string) => {
        try {
            const res = await fetch(`/api/skills/${encodeURIComponent(name)}/unload`, { method: 'POST' });
            if (res.ok) {
                setLoadedSkills(prev => { const next = new Set(prev); next.delete(name); return next; });
            }
        } catch { /* ignore */ }
    };

    const handleExpand = async (name: string) => {
        if (expandedSkill === name) {
            setExpandedSkill(null);
            return;
        }
        setExpandedSkill(name);
        setSkillDetail('Loading...');
        try {
            const res = await fetch(`/api/skills/${encodeURIComponent(name)}/detail`);
            if (res.ok) {
                const data = await res.json();
                setSkillDetail(data.instructions || 'No instructions available.');
            } else {
                const skill = skills.find(s => s.name === name);
                setSkillDetail(skill?.description || 'No details available.');
            }
        } catch {
            setSkillDetail('Failed to load details.');
        }
    };

    const handleInstall = async () => {
        if (!installSource.trim()) return;
        setInstalling(true);
        setInstallError('');
        try {
            const res = await fetch('/api/skills/install', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ source: installSource.trim() }),
            });
            const data = await res.json();
            if (!res.ok) {
                setInstallError(data.message || 'Install failed');
            } else {
                setInstallSource('');
                setShowInstall(false);
                fetchSkills();
            }
        } catch {
            setInstallError('Network error');
        }
        setInstalling(false);
    };

    const handleUninstall = async (name: string) => {
        await fetch(`/api/skills/${name}`, { method: 'DELETE' });
        fetchSkills();
    };

    const handleUpdate = async (name: string) => {
        await fetch(`/api/skills/${name}/update`, { method: 'POST' });
        fetchSkills();
    };

    const loadedCount = loadedSkills.size;

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center"
             style={{ background: 'rgba(0,0,0,0.5)' }}
             onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
            <div className="w-full max-w-2xl max-h-[80vh] flex flex-col rounded-lg overflow-hidden"
                 style={{ background: 'var(--bg-secondary)', border: '1px solid var(--border-color)' }}>
                {/* Header */}
                <div className="flex items-center justify-between px-4 py-3"
                     style={{ borderBottom: '1px solid var(--border-color)' }}>
                    <div className="flex items-center gap-2">
                        <Sparkles size={16} style={{ color: 'var(--accent-color)' }} />
                        <span className="font-semibold" style={{ color: 'var(--text-primary)' }}>Skills</span>
                        <span className="text-xs px-1.5 py-0.5 rounded"
                              style={{ background: 'var(--bg-tertiary)', color: 'var(--text-secondary)' }}>
                            {skills.length}
                        </span>
                        {loadedCount > 0 && (
                            <span className="text-xs px-1.5 py-0.5 rounded"
                                  style={{ background: '#6366f120', color: '#6366f1' }}>
                                {loadedCount} loaded
                            </span>
                        )}
                    </div>
                    <div className="flex items-center gap-2">
                        <button onClick={() => setShowInstall(!showInstall)}
                                className="text-xs px-2 py-1 rounded flex items-center gap-1"
                                style={{ background: 'var(--accent-color)', color: '#fff' }}>
                            <Download size={12} />Install
                        </button>
                        <button onClick={onClose} style={{ color: 'var(--text-secondary)' }}>
                            <X size={16} />
                        </button>
                    </div>
                </div>

                {/* Install Form */}
                {showInstall && (
                    <div className="px-4 py-3" style={{ borderBottom: '1px solid var(--border-color)' }}>
                        <div className="flex gap-2">
                            <input type="text" placeholder="owner/repo or git URL"
                                   value={installSource} onChange={e => setInstallSource(e.target.value)}
                                   onKeyDown={e => e.key === 'Enter' && handleInstall()}
                                   className="flex-1 px-2 py-1 rounded text-sm outline-none"
                                   style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)', color: 'var(--text-primary)' }} />
                            <button onClick={handleInstall} disabled={installing}
                                    className="px-3 py-1 rounded text-sm"
                                    style={{ background: 'var(--accent-color)', color: '#fff', opacity: installing ? 0.5 : 1 }}>
                                {installing ? '...' : 'Install'}
                            </button>
                        </div>
                        {installError && <p className="text-xs mt-1" style={{ color: '#ef4444' }}>{installError}</p>}
                    </div>
                )}

                {/* Tabs + Search */}
                <div className="flex items-center gap-2 px-4 py-2" style={{ borderBottom: '1px solid var(--border-color)' }}>
                    {(['all', 'managed'] as Tab[]).map(t => (
                        <button key={t} onClick={() => setTab(t)}
                                className="text-xs px-2 py-1 rounded capitalize"
                                style={{
                                    background: tab === t ? 'var(--accent-color)' : 'transparent',
                                    color: tab === t ? '#fff' : 'var(--text-secondary)',
                                }}>
                            {t === 'all' ? `All (${skills.length})` : `Managed (${managed.length})`}
                        </button>
                    ))}
                    <div className="flex-1" />
                    <div className="relative">
                        <Search size={12} className="absolute left-2 top-1/2 -translate-y-1/2" style={{ color: 'var(--text-secondary)' }} />
                        <input type="text" placeholder="Search..." value={search}
                               onChange={e => setSearch(e.target.value)}
                               className="pl-6 pr-2 py-1 rounded text-xs outline-none w-40"
                               style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)', color: 'var(--text-primary)' }} />
                    </div>
                </div>

                {/* Content */}
                <div className="flex-1 overflow-y-auto px-4 py-2" style={{ minHeight: 0 }}>
                    {loading ? (
                        <p className="text-sm text-center py-8" style={{ color: 'var(--text-secondary)' }}>Loading...</p>
                    ) : tab === 'managed' ? (
                        managed.length === 0 ? (
                            <p className="text-sm text-center py-8" style={{ color: 'var(--text-secondary)' }}>
                                No managed skills. Click Install to add from git.
                            </p>
                        ) : managed.map(s => (
                            <div key={s.name} className="py-3" style={{ borderBottom: '1px solid var(--border-color)' }}>
                                <div className="flex items-center justify-between">
                                    <span className="font-medium text-sm" style={{ color: 'var(--text-primary)' }}>{s.name}</span>
                                    <div className="flex gap-1">
                                        <button onClick={() => handleUpdate(s.name)} className="p-1 rounded hover:opacity-80"
                                                style={{ color: 'var(--text-secondary)' }} title="Update">
                                            <RefreshCw size={14} />
                                        </button>
                                        <button onClick={() => handleUninstall(s.name)} className="p-1 rounded hover:opacity-80"
                                                style={{ color: '#ef4444' }} title="Remove">
                                            <Trash2 size={14} />
                                        </button>
                                    </div>
                                </div>
                                <p className="text-xs mt-1" style={{ color: 'var(--text-secondary)' }}>{s.gitUrl}</p>
                            </div>
                        ))
                    ) : filtered.length === 0 ? (
                        <p className="text-sm text-center py-8" style={{ color: 'var(--text-secondary)' }}>No skills found.</p>
                    ) : filtered.map(s => {
                        const isLoaded = loadedSkills.has(s.name);
                        const isExpanded = expandedSkill === s.name;
                        return (
                            <div key={s.name} className="py-3" style={{ borderBottom: '1px solid var(--border-color)' }}>
                                <div className="flex items-center gap-2">
                                    <button onClick={() => handleExpand(s.name)}
                                            className="p-0.5" style={{ color: 'var(--text-secondary)' }}>
                                        {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                                    </button>
                                    <span className="font-medium text-sm" style={{ color: 'var(--text-primary)' }}>{s.name}</span>
                                    <span className="text-[10px] px-1.5 py-0.5 rounded font-medium"
                                          style={{ background: `${CATEGORY_COLORS[s.category] || '#6b7280'}20`, color: CATEGORY_COLORS[s.category] || '#6b7280' }}>
                                        {s.category}
                                    </span>
                                    <div className="flex-1" />
                                    {isLoaded ? (
                                        <button onClick={() => handleUnload(s.name)}
                                                className="text-[11px] px-2 py-0.5 rounded flex items-center gap-1"
                                                style={{ background: '#6366f120', color: '#6366f1', border: '1px solid #6366f140' }}>
                                            <Square size={10} />Unload
                                        </button>
                                    ) : (
                                        <button onClick={() => handleLoad(s.name)}
                                                className="text-[11px] px-2 py-0.5 rounded flex items-center gap-1"
                                                style={{ background: 'var(--bg-tertiary)', color: 'var(--text-secondary)', border: '1px solid var(--border-color)' }}>
                                            <Play size={10} />Load
                                        </button>
                                    )}
                                </div>
                                <p className="text-xs mt-1 ml-6" style={{ color: 'var(--text-secondary)' }}>{s.description}</p>
                                {s.triggers.length > 0 && (
                                    <div className="flex gap-1 mt-1 ml-6 flex-wrap">
                                        {s.triggers.map(t => (
                                            <span key={t} className="text-[10px] px-1.5 py-0.5 rounded"
                                                  style={{ background: 'var(--bg-primary)', color: 'var(--text-secondary)', border: '1px solid var(--border-color)' }}>
                                                {t}
                                            </span>
                                        ))}
                                    </div>
                                )}
                                {isExpanded && (
                                    <div className="mt-2 ml-6 p-3 rounded text-xs whitespace-pre-wrap"
                                         style={{ background: 'var(--bg-primary)', color: 'var(--text-secondary)', border: '1px solid var(--border-color)', maxHeight: 200, overflowY: 'auto' }}>
                                        {skillDetail}
                                    </div>
                                )}
                            </div>
                        );
                    })}
                </div>
            </div>
        </div>
    );
}
