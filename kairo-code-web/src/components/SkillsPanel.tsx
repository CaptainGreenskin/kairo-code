import { useState, useEffect, useCallback } from 'react';
import { X, Sparkles, Download, Trash2, RefreshCw, Search } from 'lucide-react';

interface Skill {
    name: string;
    description: string;
    category: string;
    priority: string;
    visibility: string;
    triggers: string[];
    version: string;
    hasInstructions: boolean;
}

interface ManagedSkill {
    name: string;
    gitUrl: string;
    path: string;
}

type Tab = 'all' | 'loaded' | 'managed';

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
    const [loading, setLoading] = useState(true);
    const [search, setSearch] = useState('');
    const [showInstall, setShowInstall] = useState(false);
    const [installSource, setInstallSource] = useState('');
    const [installError, setInstallError] = useState('');
    const [installing, setInstalling] = useState(false);

    const fetchSkills = useCallback(async () => {
        try {
            const [skillsRes, managedRes] = await Promise.all([
                fetch('/api/skills'),
                fetch('/api/skills/managed'),
            ]);
            if (skillsRes.ok) setSkills(await skillsRes.json());
            if (managedRes.ok) setManaged(await managedRes.json());
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
        if (q && !s.name.toLowerCase().includes(q) && !s.description.toLowerCase().includes(q)) return false;
        return true;
    });

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
        try {
            await fetch(`/api/skills/${name}`, { method: 'DELETE' });
            fetchSkills();
        } catch { /* ignore */ }
    };

    const handleUpdate = async (name: string) => {
        try {
            await fetch(`/api/skills/${name}/update`, { method: 'POST' });
            fetchSkills();
        } catch { /* ignore */ }
    };

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center"
             style={{ background: 'rgba(0,0,0,0.5)' }}>
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
                    </div>
                    <div className="flex items-center gap-2">
                        <button onClick={() => setShowInstall(!showInstall)}
                                className="text-xs px-2 py-1 rounded"
                                style={{ background: 'var(--accent-color)', color: '#fff' }}>
                            <Download size={12} className="inline mr-1" />Install
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
                    {(['all', 'loaded', 'managed'] as Tab[]).map(t => (
                        <button key={t} onClick={() => setTab(t)}
                                className="text-xs px-2 py-1 rounded capitalize"
                                style={{
                                    background: tab === t ? 'var(--accent-color)' : 'transparent',
                                    color: tab === t ? '#fff' : 'var(--text-secondary)',
                                }}>
                            {t === 'all' ? `All (${skills.length})` : t === 'managed' ? `Managed (${managed.length})` : 'Loaded'}
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
                                No managed skills installed. Use Install to add from git.
                            </p>
                        ) : (
                            managed.map(s => (
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
                        )
                    ) : (
                        filtered.length === 0 ? (
                            <p className="text-sm text-center py-8" style={{ color: 'var(--text-secondary)' }}>No skills found.</p>
                        ) : (
                            filtered.map(s => (
                                <div key={s.name} className="py-3" style={{ borderBottom: '1px solid var(--border-color)' }}>
                                    <div className="flex items-center gap-2">
                                        <span className="font-medium text-sm" style={{ color: 'var(--text-primary)' }}>{s.name}</span>
                                        <span className="text-[10px] px-1.5 py-0.5 rounded font-medium"
                                              style={{ background: `${CATEGORY_COLORS[s.category] || '#6b7280'}20`, color: CATEGORY_COLORS[s.category] || '#6b7280' }}>
                                            {s.category}
                                        </span>
                                        <span className="text-[10px] px-1 py-0.5 rounded"
                                              style={{ background: 'var(--bg-tertiary)', color: 'var(--text-secondary)' }}>
                                            {s.priority}
                                        </span>
                                    </div>
                                    <p className="text-xs mt-1" style={{ color: 'var(--text-secondary)' }}>{s.description}</p>
                                    {s.triggers.length > 0 && (
                                        <div className="flex gap-1 mt-1 flex-wrap">
                                            {s.triggers.map(t => (
                                                <span key={t} className="text-[10px] px-1.5 py-0.5 rounded"
                                                      style={{ background: 'var(--bg-primary)', color: 'var(--text-secondary)', border: '1px solid var(--border-color)' }}>
                                                    {t}
                                                </span>
                                            ))}
                                        </div>
                                    )}
                                </div>
                            ))
                        )
                    )}
                </div>
            </div>
        </div>
    );
}
