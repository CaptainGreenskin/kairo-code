import { useState, useEffect, useRef, useMemo } from 'react';
import { X, Eye, EyeOff, Check, Settings2, ChevronRight, KeyRound, Cpu, Plug, Info, ExternalLink } from 'lucide-react';
import type { ServerConfig } from '@/types/agent';
import { updateConfig, getModels, getProviders, type ProviderInfo } from '@api/config';
import { getAuthToken, setAuthToken } from '@/api/auth';

interface SettingsModalProps {
    isOpen: boolean;
    onClose: () => void;
    config: ServerConfig;
    onSaved: (newConfig: ServerConfig) => void;
    onOpenMcpServers?: () => void;
}

// Fallback list — shown only on the brief flash before /api/providers responds,
// or if the server is unreachable. Source of truth is the backend
// (kairo-code-core/.../ProviderRegistry → /api/providers); hardcoded fallback
// here is just so a fresh modal isn't empty during the fetch.
const FALLBACK_PROVIDERS: ProviderInfo[] = [
    { id: 'openai', displayName: 'OpenAI', defaultBaseUrl: 'https://api.openai.com', defaultModel: 'gpt-4o', knownModels: [] },
    { id: 'anthropic', displayName: 'Anthropic', defaultBaseUrl: 'https://api.anthropic.com', defaultModel: 'claude-sonnet-4-20250514', knownModels: [] },
    { id: 'glm', displayName: '智谱 (GLM)', defaultBaseUrl: 'https://open.bigmodel.cn/api/coding/paas/v4', defaultModel: 'glm-5.1', knownModels: [] },
    { id: 'qianwen', displayName: '通义千问', defaultBaseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', defaultModel: 'qwen-max', knownModels: [] },
];

type SectionId = 'account' | 'model' | 'integrations' | 'about';

const SECTIONS: { id: SectionId; label: string; icon: typeof KeyRound; description: string }[] = [
    { id: 'account', label: 'Account', icon: KeyRound, description: 'Provider and credentials' },
    { id: 'model', label: 'Model', icon: Cpu, description: 'Model name and reasoning budget' },
    { id: 'integrations', label: 'Integrations', icon: Plug, description: 'MCP servers' },
    { id: 'about', label: 'About', icon: Info, description: 'Version and links' },
];

export function SettingsModal({ isOpen, onClose, config, onSaved, onOpenMcpServers }: SettingsModalProps) {
    const [activeSection, setActiveSection] = useState<SectionId>('account');
    const [provider, setProvider] = useState(config.provider || 'openai');
    const [apiKey, setApiKey] = useState('');
    const [model, setModel] = useState(config.model || '');
    const [baseUrl, setBaseUrl] = useState(config.baseUrl || '');
    const [serverToken, setServerToken] = useState(getAuthToken());
    const [showServerToken, setShowServerToken] = useState(false);
    const [localThinkingBudget, setLocalThinkingBudget] = useState(String(config.thinkingBudget ?? 0));
    const [showApiKey, setShowApiKey] = useState(false);
    const [saving, setSaving] = useState(false);
    const [saved, setSaved] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [availableModels, setAvailableModels] = useState<string[]>([]);
    const [providers, setProviders] = useState<ProviderInfo[]>(FALLBACK_PROVIDERS);
    const overlayRef = useRef<HTMLDivElement>(null);

    // Lookup tables derived from /api/providers — replace the old hardcoded
    // PROVIDER_BASE_URLS / PROVIDER_DEFAULT_MODELS that drifted from backend.
    const providerBaseUrls = useMemo(() => {
        const m: Record<string, string> = {};
        for (const p of providers) m[p.id] = p.defaultBaseUrl;
        return m;
    }, [providers]);
    const providerDefaultModels = useMemo(() => {
        const m: Record<string, string> = {};
        for (const p of providers) m[p.id] = p.defaultModel;
        return m;
    }, [providers]);

    useEffect(() => {
        if (!isOpen) return;
        const handleEsc = (e: KeyboardEvent) => {
            if (e.key === 'Escape') onClose();
        };
        window.addEventListener('keydown', handleEsc);
        return () => window.removeEventListener('keydown', handleEsc);
    }, [isOpen, onClose]);

    useEffect(() => {
        if (!isOpen) return;
        getModels().then(setAvailableModels).catch(() => {});
        getProviders().then(setProviders).catch(() => {});
    }, [isOpen]);

    // Reset section + transient state when modal opens
    useEffect(() => {
        if (isOpen) {
            setActiveSection('account');
            setError(null);
            setSaved(false);
            setApiKey('');
            setProvider(config.provider || 'openai');
            setModel(config.model || '');
            setBaseUrl(config.baseUrl || '');
            setServerToken(getAuthToken());
            setLocalThinkingBudget(String(config.thinkingBudget ?? 0));
        }
    }, [isOpen, config]);

    if (!isOpen) return null;

    const handleProviderChange = (p: string) => {
        setProvider(p);
        if (providerBaseUrls[p]) {
            setBaseUrl(providerBaseUrls[p]);
        } else if (!providerBaseUrls[provider]) {
            setBaseUrl('');
        }
        if (providerDefaultModels[p] && !model) {
            setModel(providerDefaultModels[p]);
        }
    };

    const handleSave = async () => {
        setSaving(true);
        setError(null);
        // Server access token is a client-only credential (localStorage), not part
        // of the server config payload. Persist it before the network call so the
        // updateConfig request itself carries the new token.
        setAuthToken(serverToken.trim());
        try {
            const req: Parameters<typeof updateConfig>[0] = {};
            if (apiKey) req.apiKey = apiKey;
            if (model) req.model = model;
            if (provider) req.provider = provider;
            if ((provider === 'custom' || providerBaseUrls[provider]) && baseUrl) req.baseUrl = baseUrl;
            req.thinkingBudget = parseInt(localThinkingBudget) || 0;

            const newConfig = await updateConfig(req);
            setSaved(true);
            setTimeout(() => {
                setSaved(false);
                onClose();
                onSaved(newConfig);
            }, 1200);
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed to save settings');
        } finally {
            setSaving(false);
        }
    };

    const handleOverlayClick = (e: React.MouseEvent) => {
        if (e.target === overlayRef.current) onClose();
    };

    const inputClass =
        'w-full px-3 py-2 text-sm rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] text-[var(--text-primary)] focus:outline-none focus:ring-2 focus:ring-[var(--color-focus-ring)]';
    const labelClass = 'block text-xs font-medium text-[var(--text-secondary)] mb-1';
    const helpClass = 'text-xs text-[var(--text-muted)] mt-1';

    return (
        <div
            ref={overlayRef}
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
            onClick={handleOverlayClick}
        >
            <div className="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl shadow-2xl w-full max-w-3xl mx-4 h-[600px] max-h-[85vh] flex flex-col overflow-hidden">
                {/* Header */}
                <div className="flex items-center justify-between px-5 py-3 border-b border-[var(--border)] shrink-0">
                    <h2 className="text-sm font-semibold text-[var(--text-primary)]">Settings</h2>
                    <button
                        onClick={onClose}
                        className="text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
                        aria-label="Close settings"
                    >
                        <X size={18} />
                    </button>
                </div>

                {/* Body: nav + content */}
                <div className="flex-1 flex min-h-0">
                    {/* Left nav */}
                    <nav className="w-44 shrink-0 border-r border-[var(--border)] py-3 px-2 space-y-0.5 overflow-y-auto">
                        {SECTIONS.map((s) => {
                            const Icon = s.icon;
                            const active = activeSection === s.id;
                            return (
                                <button
                                    key={s.id}
                                    onClick={() => setActiveSection(s.id)}
                                    className={`w-full flex items-center gap-2 px-2.5 py-1.5 text-sm rounded transition-colors text-left ${
                                        active
                                            ? 'bg-[var(--bg-primary)] text-[var(--text-primary)]'
                                            : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-primary)]/50'
                                    }`}
                                >
                                    <Icon size={14} className={active ? 'text-[var(--accent)]' : ''} />
                                    {s.label}
                                </button>
                            );
                        })}
                    </nav>

                    {/* Content */}
                    <div className="flex-1 overflow-y-auto px-6 py-5">
                        {error && (
                            <div className="mb-4 text-sm text-[var(--color-danger)] bg-[var(--color-danger-bg)] rounded-lg px-3 py-2">
                                {error}
                            </div>
                        )}

                        {activeSection === 'account' && (
                            <div className="space-y-5">
                                <SectionHeader title="Account" subtitle="LLM provider and credentials." />

                                <div>
                                    <label className={labelClass}>Provider</label>
                                    <select
                                        className={inputClass}
                                        value={provider}
                                        onChange={(e) => handleProviderChange(e.target.value)}
                                    >
                                        {providers.map((p) => (
                                            <option key={p.id} value={p.id}>
                                                {p.displayName}
                                            </option>
                                        ))}
                                        <option value="custom">Custom</option>
                                    </select>
                                </div>

                                <div>
                                    <label className={labelClass}>
                                        API Key{' '}
                                        <span className="text-[var(--text-muted)]">
                                            {config.apiKeySet ? '(already set, leave blank to keep)' : '(required)'}
                                        </span>
                                    </label>
                                    <div className="relative">
                                        <input
                                            type={showApiKey ? 'text' : 'password'}
                                            className={`${inputClass} pr-10`}
                                            placeholder={config.apiKeySet ? '••••••••' : 'sk-...'}
                                            value={apiKey}
                                            onChange={(e) => setApiKey(e.target.value)}
                                        />
                                        <button
                                            type="button"
                                            className="absolute right-2 top-1/2 -translate-y-1/2 text-[var(--text-secondary)] hover:text-[var(--text-primary)]"
                                            onClick={() => setShowApiKey(!showApiKey)}
                                            aria-label={showApiKey ? 'Hide API key' : 'Show API key'}
                                        >
                                            {showApiKey ? <EyeOff size={16} /> : <Eye size={16} />}
                                        </button>
                                    </div>
                                </div>

                                {(provider === 'custom' || providerBaseUrls[provider]) && (
                                    <div>
                                        <label className={labelClass}>Base URL</label>
                                        <input
                                            className={inputClass}
                                            placeholder="https://api.openai.com/v1"
                                            value={baseUrl}
                                            onChange={(e) => setBaseUrl(e.target.value)}
                                        />
                                        <p className={helpClass}>Override the default endpoint for this provider.</p>
                                    </div>
                                )}

                                <div>
                                    <label className={labelClass}>
                                        Server Access Token{' '}
                                        <span className="text-[var(--text-muted)]">(for remote/secured servers)</span>
                                    </label>
                                    <div className="relative">
                                        <input
                                            type={showServerToken ? 'text' : 'password'}
                                            className={`${inputClass} pr-10`}
                                            placeholder="Leave blank for local (loopback) servers"
                                            value={serverToken}
                                            onChange={(e) => setServerToken(e.target.value)}
                                        />
                                        <button
                                            type="button"
                                            className="absolute right-2 top-1/2 -translate-y-1/2 text-[var(--text-secondary)] hover:text-[var(--text-primary)]"
                                            onClick={() => setShowServerToken(!showServerToken)}
                                            aria-label={showServerToken ? 'Hide token' : 'Show token'}
                                        >
                                            {showServerToken ? <EyeOff size={16} /> : <Eye size={16} />}
                                        </button>
                                    </div>
                                    <p className={helpClass}>
                                        Sent as a bearer token on every API/WebSocket request. Must match the server's
                                        KAIRO_SERVER_AUTH_TOKEN. Stored only in this browser.
                                    </p>
                                </div>
                            </div>
                        )}

                        {activeSection === 'model' && (
                            <div className="space-y-5">
                                <SectionHeader title="Model" subtitle="Default model and reasoning controls." />

                                <div>
                                    <label className={labelClass}>Model</label>
                                    <input
                                        className={inputClass}
                                        placeholder="gpt-4o"
                                        value={model}
                                        onChange={(e) => setModel(e.target.value)}
                                        list="model-suggestions"
                                    />
                                    <datalist id="model-suggestions">
                                        {availableModels.map((m) => (
                                            <option key={m} value={m} />
                                        ))}
                                    </datalist>
                                    {availableModels.length > 0 && (
                                        <p className={helpClass}>{availableModels.length} model{availableModels.length !== 1 ? 's' : ''} discovered for this provider.</p>
                                    )}
                                </div>

                                <div>
                                    <label className={labelClass}>Extended Thinking Budget (tokens)</label>
                                    <input
                                        type="number"
                                        min="0"
                                        max="32000"
                                        step="1000"
                                        className={inputClass}
                                        value={localThinkingBudget}
                                        onChange={(e) => setLocalThinkingBudget(e.target.value)}
                                    />
                                    <p className={helpClass}>0 = disabled. Requires claude-3.7+ or claude-sonnet-4.</p>
                                </div>
                            </div>
                        )}

                        {activeSection === 'integrations' && (
                            <div className="space-y-5">
                                <SectionHeader title="Integrations" subtitle="External tool servers." />

                                {onOpenMcpServers ? (
                                    <button
                                        onClick={() => { onClose(); onOpenMcpServers(); }}
                                        className="w-full flex items-center gap-3 px-4 py-3 rounded-lg border border-[var(--border)] hover:border-[var(--accent)]/50 bg-[var(--bg-primary)] hover:bg-[var(--bg-hover)] transition-colors text-left group"
                                    >
                                        <div className="p-2 rounded-md bg-[var(--bg-secondary)] text-[var(--accent)]">
                                            <Settings2 size={16} />
                                        </div>
                                        <div className="flex-1">
                                            <div className="text-sm font-medium text-[var(--text-primary)]">MCP Servers</div>
                                            <div className="text-xs text-[var(--text-muted)]">Configure Model Context Protocol servers</div>
                                        </div>
                                        <ChevronRight size={14} className="text-[var(--text-muted)] group-hover:text-[var(--text-primary)] transition-colors" />
                                    </button>
                                ) : (
                                    <p className="text-sm text-[var(--text-muted)]">No integrations available.</p>
                                )}
                            </div>
                        )}

                        {activeSection === 'about' && (
                            <div className="space-y-5">
                                <SectionHeader title="About" subtitle="Kairo Code — Java AI Code Agent." />

                                <div className="space-y-2 text-sm">
                                    <Row label="App" value="Kairo Code" />
                                    <Row label="Framework" value="Kairo (Java Agent OS)" />
                                </div>

                                <div className="pt-2 space-y-2">
                                    <a
                                        href="https://github.com/CaptainGreenskin/kairo-code"
                                        target="_blank"
                                        rel="noreferrer"
                                        className="flex items-center gap-2 text-sm text-[var(--accent)] hover:underline"
                                    >
                                        <ExternalLink size={13} /> GitHub
                                    </a>
                                </div>
                            </div>
                        )}
                    </div>
                </div>

                {/* Footer */}
                <div className="flex justify-end items-center gap-2 px-5 py-3 border-t border-[var(--border)] shrink-0 bg-[var(--bg-secondary)]">
                    <button
                        className="px-4 py-1.5 text-sm text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
                        onClick={onClose}
                        disabled={saving}
                    >
                        Cancel
                    </button>
                    <button
                        className="px-4 py-1.5 text-sm bg-[var(--color-primary)] hover:bg-[var(--color-primary-hover)] text-white rounded-lg transition-colors disabled:opacity-50 min-w-[80px]"
                        onClick={handleSave}
                        disabled={saving}
                    >
                        {saved ? (
                            <span className="flex items-center justify-center gap-1">
                                <Check size={14} /> Saved
                            </span>
                        ) : saving ? (
                            'Saving…'
                        ) : (
                            'Save'
                        )}
                    </button>
                </div>
            </div>
        </div>
    );
}

function SectionHeader({ title, subtitle }: { title: string; subtitle: string }) {
    return (
        <div>
            <h3 className="text-sm font-semibold text-[var(--text-primary)]">{title}</h3>
            <p className="text-xs text-[var(--text-muted)] mt-0.5">{subtitle}</p>
        </div>
    );
}

function Row({ label, value }: { label: string; value: string }) {
    return (
        <div className="flex justify-between border-b border-[var(--border)]/50 pb-1.5">
            <span className="text-[var(--text-muted)]">{label}</span>
            <span className="text-[var(--text-primary)]">{value}</span>
        </div>
    );
}
