import { useState, useEffect, useRef } from 'react';
import { X, Eye, EyeOff, Check, Settings2, ChevronRight } from 'lucide-react';
import type { ServerConfig } from '@/types/agent';
import { updateConfig, getModels } from '@api/config';

interface SettingsModalProps {
    isOpen: boolean;
    onClose: () => void;
    config: ServerConfig;
    onSaved: (newConfig: ServerConfig) => void;
    onOpenMcpServers?: () => void;
}

const PROVIDERS = [
    { value: 'openai', label: 'OpenAI' },
    { value: 'anthropic', label: 'Anthropic' },
    { value: 'zhipu', label: 'Zhipu' },
    { value: 'custom', label: 'Custom' },
];

export function SettingsModal({ isOpen, onClose, config, onSaved, onOpenMcpServers }: SettingsModalProps) {
    const [provider, setProvider] = useState(config.provider || 'openai');
    const [apiKey, setApiKey] = useState('');
    const [model, setModel] = useState(config.model || '');
    const [baseUrl, setBaseUrl] = useState(config.baseUrl || '');
    const [workingDir, setWorkingDir] = useState(config.workingDir || '');
    const [showApiKey, setShowApiKey] = useState(false);
    const [saving, setSaving] = useState(false);
    const [saved, setSaved] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [availableModels, setAvailableModels] = useState<string[]>([]);
    const overlayRef = useRef<HTMLDivElement>(null);

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
    }, [isOpen]);

    if (!isOpen) return null;

    const handleSave = async () => {
        setSaving(true);
        setError(null);
        try {
            const req: Parameters<typeof updateConfig>[0] = {};
            if (apiKey) req.apiKey = apiKey;
            if (model) req.model = model;
            if (provider) req.provider = provider;
            if (provider === 'custom' && baseUrl) req.baseUrl = baseUrl;
            if (workingDir) req.workingDir = workingDir;

            const newConfig = await updateConfig(req);
            setSaved(true);
            setTimeout(() => {
                setSaved(false);
                onClose();
                onSaved(newConfig);
            }, 1500);
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

    return (
        <div
            ref={overlayRef}
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
            onClick={handleOverlayClick}
        >
            <div className="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl shadow-xl w-full max-w-md mx-4">
                {/* Header */}
                <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--border)]">
                    <h2 className="text-sm font-semibold text-[var(--text-primary)]">Settings</h2>
                    <button
                        onClick={onClose}
                        className="text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
                        aria-label="Close settings"
                    >
                        <X size={18} />
                    </button>
                </div>

                {/* Form */}
                <div className="px-4 py-4 space-y-4">
                    {error && (
                        <div className="text-sm text-[var(--color-danger)] bg-[var(--color-danger-bg)] rounded-lg px-3 py-2">
                            {error}
                        </div>
                    )}

                    {/* Provider */}
                    <div>
                        <label className={labelClass}>Provider</label>
                        <select
                            className={inputClass}
                            value={provider}
                            onChange={(e) => setProvider(e.target.value)}
                        >
                            {PROVIDERS.map((p) => (
                                <option key={p.value} value={p.value}>
                                    {p.label}
                                </option>
                            ))}
                        </select>
                    </div>

                    {/* API Key */}
                    <div>
                        <label className={labelClass}>
                            API Key{' '}
                            <span className="text-[var(--text-muted)]">
                                {config.apiKeySet ? '(already set)' : '(required)'}
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

                    {/* Model */}
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
                    </div>

                    {/* Base URL (conditional) */}
                    {provider === 'custom' && (
                        <div>
                            <label className={labelClass}>Base URL</label>
                            <input
                                className={inputClass}
                                placeholder="https://api.openai.com/v1"
                                value={baseUrl}
                                onChange={(e) => setBaseUrl(e.target.value)}
                            />
                        </div>
                    )}

                    {/* Working Directory */}
                    <div>
                        <label className={labelClass}>Working Directory</label>
                        <input
                            className={inputClass}
                            placeholder="~/kairo-workspace"
                            value={workingDir}
                            onChange={(e) => setWorkingDir(e.target.value)}
                        />
                    </div>

                    {/* Actions */}
                    <div className="flex justify-end gap-2 pt-2">
                        <button
                            className="px-4 py-2 text-sm text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
                            onClick={onClose}
                            disabled={saving}
                        >
                            Cancel
                        </button>
                        <button
                            className="px-4 py-2 text-sm bg-[var(--color-primary)] hover:bg-[var(--color-primary-hover)] text-white rounded-lg transition-colors disabled:opacity-50"
                            onClick={handleSave}
                            disabled={saving}
                        >
                            {saved ? (
                                <span className="flex items-center gap-1">
                                    <Check size={14} /> Saved
                                </span>
                            ) : saving ? (
                                'Saving...'
                            ) : (
                                'Save'
                            )}
                        </button>
                    </div>

                    {onOpenMcpServers && (
                        <div className="pt-3 border-t border-[var(--border)]">
                            <button
                                onClick={() => { onClose(); onOpenMcpServers(); }}
                                className="w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm
                                    text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)] transition-colors"
                            >
                                <Settings2 size={15} />
                                MCP Servers
                                <ChevronRight size={12} className="ml-auto" />
                            </button>
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
}
