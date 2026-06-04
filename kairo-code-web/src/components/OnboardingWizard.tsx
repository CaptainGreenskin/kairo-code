import { useState, useEffect, useCallback, useMemo } from 'react';
import { Sparkles, Eye, EyeOff, Check, Loader2, ChevronLeft, ChevronRight, FolderOpen, Folder, SkipForward, Globe, Cpu } from 'lucide-react';
import { updateConfig, getModels, getProviders, getDirs, chooseDir, type ProviderInfo, type DirEntry } from '@api/config';
import type { ServerConfig } from '@/types/agent';
import { useWorkspaceStore } from '@/store/workspaceStore';

interface OnboardingWizardProps {
    onComplete: (config: ServerConfig) => void;
    onSkip: () => void;
}

const ONBOARDING_DONE_KEY = 'kairo.onboarding.done';

export function isOnboardingDone(): boolean {
    try {
        return localStorage.getItem(ONBOARDING_DONE_KEY) === '1';
    } catch {
        return false;
    }
}

export function markOnboardingDone(): void {
    try {
        localStorage.setItem(ONBOARDING_DONE_KEY, '1');
    } catch { /* ignore */ }
}

type ProviderGroup = 'glm' | 'anthropic';

const STEPS = [
    { label: '选择 Provider' },
    { label: '配置 API Key' },
    { label: '打开项目' },
];

export function OnboardingWizard({ onComplete, onSkip }: OnboardingWizardProps) {
    const [step, setStep] = useState(0);

    // Step 1 state
    const [selectedGroup, setSelectedGroup] = useState<ProviderGroup>('glm');

    // Step 2 state
    const [providers, setProviders] = useState<ProviderInfo[]>([]);
    const [provider, setProvider] = useState('glm');
    const [apiKey, setApiKey] = useState('');
    const [model, setModel] = useState('');
    const [baseUrl, setBaseUrl] = useState('');
    const [showApiKey, setShowApiKey] = useState(false);
    const [testing, setTesting] = useState(false);
    const [testResult, setTestResult] = useState<{ ok: boolean; msg: string } | null>(null);

    // Step 3 state
    const [pathInput, setPathInput] = useState('');
    const [browsePath, setBrowsePath] = useState('');
    const [dirs, setDirs] = useState<DirEntry[]>([]);
    const [loadingDirs, setLoadingDirs] = useState(false);
    const [dirError, setDirError] = useState<string | null>(null);
    const [showBrowser, setShowBrowser] = useState(false);
    const [creatingWorkspace, setCreatingWorkspace] = useState(false);

    const createWorkspace = useWorkspaceStore((s) => s.create);

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
        getProviders().then(setProviders).catch(() => {});
    }, []);

    // When provider group changes, set default provider and model
    useEffect(() => {
        if (selectedGroup === 'glm') {
            setProvider('glm');
            setModel(providerDefaultModels['glm'] || 'glm-5.1');
            setBaseUrl('');
        } else {
            setProvider('anthropic');
            setModel(providerDefaultModels['anthropic'] || 'claude-sonnet-4-20250514');
            setBaseUrl(providerBaseUrls['anthropic'] || 'https://api.anthropic.com');
        }
        setTestResult(null);
    }, [selectedGroup, providerDefaultModels, providerBaseUrls]);

    // Load dirs for step 3
    const loadDirs = useCallback((path: string) => {
        setLoadingDirs(true);
        setDirError(null);
        getDirs(path)
            .then((entries) => {
                setDirs(entries);
                setBrowsePath(path || '~');
            })
            .catch(() => setDirError('无法读取目录'))
            .finally(() => setLoadingDirs(false));
    }, []);

    useEffect(() => {
        if (step === 2 && !showBrowser) {
            // preload home dirs so browser opens instantly
            getDirs('').then(setDirs).catch(() => {});
        }
    }, [step, showBrowser]);

    const handleTestConnection = async () => {
        setTesting(true);
        setTestResult(null);
        try {
            const req: Record<string, unknown> = {};
            if (apiKey) req.apiKey = apiKey;
            if (model) req.model = model;
            if (provider) req.provider = provider;
            if (baseUrl) req.baseUrl = baseUrl;

            await updateConfig(req as Parameters<typeof updateConfig>[0]);
            const models = await getModels();
            setTestResult({
                ok: true,
                msg: `连接成功！发现 ${models.length} 个模型`,
            });
        } catch (e) {
            setTestResult({
                ok: false,
                msg: e instanceof Error ? e.message : '连接失败',
            });
        } finally {
            setTesting(false);
        }
    };

    const handleSaveAndNext = async () => {
        if (!testResult?.ok) {
            await handleTestConnection();
        }
        setStep(2);
    };

    const handleFinish = async (dir?: string) => {
        const workingDir = dir || pathInput;
        if (workingDir) {
            setCreatingWorkspace(true);
            try {
                const folderName = workingDir.split('/').filter(Boolean).pop() || 'Project';
                await createWorkspace({ name: folderName, workingDir });
            } catch { /* workspace creation is best-effort */ }
            setCreatingWorkspace(false);
        }

        // Save final config
        try {
            const req: Record<string, unknown> = {};
            if (apiKey) req.apiKey = apiKey;
            if (model) req.model = model;
            if (provider) req.provider = provider;
            if (baseUrl) req.baseUrl = baseUrl;
            const cfg = await updateConfig(req as Parameters<typeof updateConfig>[0]);
            markOnboardingDone();
            onComplete(cfg);
        } catch {
            markOnboardingDone();
            onSkip();
        }
    };

    const handleSkipProject = () => {
        handleFinish();
    };

    const goUp = () => {
        const parts = browsePath.replace(/\/$/, '').split('/');
        if (parts.length <= 1) return;
        parts.pop();
        loadDirs(parts.join('/') || '/');
    };

    return (
        <div className="flex flex-col items-center justify-center h-full px-6 py-12 select-none animate-fade-in">
            {/* Progress dots */}
            <div className="flex items-center gap-1.5 mb-10">
                {STEPS.map((_, i) => (
                    <div key={i} className="flex items-center gap-1.5">
                        {i > 0 && (
                            <div
                                className={`w-6 h-0.5 rounded-full transition-colors duration-300 ${
                                    i <= step ? 'bg-[var(--accent)]' : 'bg-[var(--border)]'
                                }`}
                            />
                        )}
                        <div
                            className={`rounded-full transition-all duration-300 ${
                                i === step
                                    ? 'w-6 h-2 bg-[var(--accent)]'
                                    : i < step
                                    ? 'w-2 h-2 bg-[var(--accent)]'
                                    : 'w-2 h-2 bg-[var(--text-muted)]/30'
                            }`}
                        />
                    </div>
                ))}
            </div>

            {/* Step 1: Provider Selection */}
            {step === 0 && (
                <div className="flex flex-col items-center w-full max-w-xl animate-fade-in">
                    <Sparkles size={32} className="text-[var(--accent)] mb-4" />
                    <h1 className="text-2xl font-bold text-[var(--text-primary)] mb-2">
                        欢迎使用 Kairo Code
                    </h1>
                    <p className="text-sm text-[var(--text-muted)] mb-8">
                        选择你的 AI 模型提供商
                    </p>

                    <div className="grid grid-cols-2 gap-4 w-full mb-8">
                        {/* GLM / OpenAI Card */}
                        <button
                            onClick={() => setSelectedGroup('glm')}
                            className={`relative flex flex-col items-start p-5 rounded-xl border-2 transition-all duration-200 text-left min-h-[140px] ${
                                selectedGroup === 'glm'
                                    ? 'border-[var(--accent)] bg-[var(--accent)]/5 shadow-[0_0_24px_rgba(99,102,241,0.15)]'
                                    : 'border-[var(--border)] bg-[var(--bg-secondary)] hover:border-[var(--text-muted)]/40 hover:bg-[var(--bg-tertiary)]'
                            }`}
                        >
                            <div className={`w-9 h-9 rounded-lg flex items-center justify-center mb-3 ${
                                selectedGroup === 'glm' ? 'bg-[var(--accent)]/20' : 'bg-[var(--bg-tertiary)]'
                            }`}>
                                <Globe size={18} className={selectedGroup === 'glm' ? 'text-[var(--accent)]' : 'text-[var(--text-muted)]'} />
                            </div>
                            <span className="text-base font-semibold text-[var(--text-primary)] mb-1">
                                GLM / OpenAI
                            </span>
                            <span className="text-xs text-[var(--text-muted)] leading-relaxed">
                                GLM-5.1, DeepSeek, Qwen,{'\n'}OpenAI 及所有兼容端点
                            </span>
                        </button>

                        {/* Anthropic Card */}
                        <button
                            onClick={() => setSelectedGroup('anthropic')}
                            className={`relative flex flex-col items-start p-5 rounded-xl border-2 transition-all duration-200 text-left min-h-[140px] ${
                                selectedGroup === 'anthropic'
                                    ? 'border-[var(--accent)] bg-[var(--accent)]/5 shadow-[0_0_24px_rgba(99,102,241,0.15)]'
                                    : 'border-[var(--border)] bg-[var(--bg-secondary)] hover:border-[var(--text-muted)]/40 hover:bg-[var(--bg-tertiary)]'
                            }`}
                        >
                            <div className={`w-9 h-9 rounded-lg flex items-center justify-center mb-3 ${
                                selectedGroup === 'anthropic' ? 'bg-[var(--accent)]/20' : 'bg-[var(--bg-tertiary)]'
                            }`}>
                                <Cpu size={18} className={selectedGroup === 'anthropic' ? 'text-[var(--accent)]' : 'text-[var(--text-muted)]'} />
                            </div>
                            <span className="text-base font-semibold text-[var(--text-primary)] mb-1">
                                Anthropic
                            </span>
                            <span className="text-xs text-[var(--text-muted)] leading-relaxed">
                                Claude Opus / Sonnet / Haiku
                            </span>
                        </button>
                    </div>

                    <div className="flex justify-end w-full">
                        <button
                            onClick={() => setStep(1)}
                            className="px-5 py-2 text-sm font-medium text-white rounded-lg transition-colors"
                            style={{ background: 'linear-gradient(135deg, #6366f1, #8b5cf6)' }}
                        >
                            下一步
                        </button>
                    </div>
                </div>
            )}

            {/* Step 2: API Key Configuration */}
            {step === 1 && (
                <div className="flex flex-col items-center w-full max-w-xl animate-fade-in">
                    <h2 className="text-xl font-bold text-[var(--text-primary)] mb-1">
                        配置 API Key
                    </h2>
                    <p className="text-sm text-[var(--text-muted)] mb-6">
                        {selectedGroup === 'glm'
                            ? '填写你的 API Key，Base URL 留空将自动检测'
                            : '填写你的 Anthropic API Key'}
                    </p>

                    <div className="w-full space-y-4">
                        {/* Provider sub-select for GLM group */}
                        {selectedGroup === 'glm' && (
                            <div>
                                <label className="block text-xs font-medium text-[var(--text-secondary)] mb-1.5">
                                    Provider
                                </label>
                                <select
                                    className="w-full px-3 py-2.5 text-sm rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] text-[var(--text-primary)] focus:outline-none focus:ring-2 focus:ring-[var(--accent)]/50"
                                    value={provider}
                                    onChange={(e) => {
                                        setProvider(e.target.value);
                                        if (providerDefaultModels[e.target.value]) {
                                            setModel(providerDefaultModels[e.target.value]);
                                        }
                                        if (providerBaseUrls[e.target.value]) {
                                            setBaseUrl(providerBaseUrls[e.target.value]);
                                        } else {
                                            setBaseUrl('');
                                        }
                                        setTestResult(null);
                                    }}
                                >
                                    {providers
                                        .filter((p) => p.id !== 'anthropic')
                                        .map((p) => (
                                            <option key={p.id} value={p.id}>
                                                {p.displayName}
                                            </option>
                                        ))}
                                    <option value="custom">Custom (自定义)</option>
                                </select>
                            </div>
                        )}

                        {/* API Key */}
                        <div>
                            <label className="block text-xs font-medium text-[var(--text-secondary)] mb-1.5">
                                API Key
                            </label>
                            <div className="relative">
                                <input
                                    type={showApiKey ? 'text' : 'password'}
                                    className="w-full px-3 py-2.5 pr-10 text-sm rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] text-[var(--text-primary)] focus:outline-none focus:ring-2 focus:ring-[var(--accent)]/50"
                                    placeholder="sk-..."
                                    value={apiKey}
                                    onChange={(e) => { setApiKey(e.target.value); setTestResult(null); }}
                                />
                                <button
                                    type="button"
                                    className="absolute right-2.5 top-1/2 -translate-y-1/2 text-[var(--text-muted)] hover:text-[var(--text-primary)]"
                                    onClick={() => setShowApiKey(!showApiKey)}
                                >
                                    {showApiKey ? <EyeOff size={16} /> : <Eye size={16} />}
                                </button>
                            </div>
                        </div>

                        {/* Model */}
                        <div>
                            <label className="block text-xs font-medium text-[var(--text-secondary)] mb-1.5">
                                Model
                            </label>
                            <input
                                className="w-full px-3 py-2.5 text-sm rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] text-[var(--text-primary)] focus:outline-none focus:ring-2 focus:ring-[var(--accent)]/50"
                                placeholder={providerDefaultModels[provider] || 'gpt-4o'}
                                value={model}
                                onChange={(e) => setModel(e.target.value)}
                            />
                        </div>

                        {/* Base URL */}
                        <div>
                            <label className="block text-xs font-medium text-[var(--text-secondary)] mb-1.5">
                                Base URL
                                {selectedGroup === 'glm' && (
                                    <span className="text-[var(--text-muted)] font-normal ml-1">
                                        (GLM 留空自动检测)
                                    </span>
                                )}
                            </label>
                            <input
                                className="w-full px-3 py-2.5 text-sm rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] text-[var(--text-primary)] focus:outline-none focus:ring-2 focus:ring-[var(--accent)]/50"
                                placeholder={providerBaseUrls[provider] || 'https://api.openai.com/v1'}
                                value={baseUrl}
                                onChange={(e) => setBaseUrl(e.target.value)}
                            />
                        </div>

                        {/* Test Connection Button */}
                        <div className="flex items-center gap-3">
                            <button
                                onClick={handleTestConnection}
                                disabled={testing || !apiKey}
                                className="px-4 py-2 text-sm font-medium rounded-lg border border-[var(--accent)] text-[var(--accent)] hover:bg-[var(--accent)]/10 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                            >
                                {testing ? (
                                    <span className="flex items-center gap-2">
                                        <Loader2 size={14} className="animate-spin" />
                                        测试中…
                                    </span>
                                ) : (
                                    '测试连接'
                                )}
                            </button>
                            {testResult && (
                                <span className={`text-xs ${testResult.ok ? 'text-emerald-400' : 'text-red-400'}`}>
                                    {testResult.ok && <Check size={13} className="inline mr-1" />}
                                    {testResult.msg}
                                </span>
                            )}
                        </div>
                    </div>

                    {/* Navigation */}
                    <div className="flex justify-between w-full mt-8">
                        <button
                            onClick={() => setStep(0)}
                            className="flex items-center gap-1 px-4 py-2 text-sm text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
                        >
                            <ChevronLeft size={16} />
                            上一步
                        </button>
                        <button
                            onClick={handleSaveAndNext}
                            disabled={!apiKey}
                            className="px-5 py-2 text-sm font-medium text-white rounded-lg transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                            style={{ background: apiKey ? 'linear-gradient(135deg, #6366f1, #8b5cf6)' : undefined }}
                        >
                            下一步
                        </button>
                    </div>
                </div>
            )}

            {/* Step 3: Open Project — Cursor-style */}
            {step === 2 && (
                <div className="flex flex-col items-center w-full max-w-md animate-fade-in">
                    <FolderOpen size={32} className="text-[var(--accent)] mb-4" />
                    <h2 className="text-xl font-bold text-[var(--text-primary)] mb-1">
                        打开一个项目
                    </h2>
                    <p className="text-sm text-[var(--text-muted)] mb-8">
                        输入项目路径或浏览选择
                    </p>

                    {/* Path input + browse button */}
                    <div className="w-full mb-2">
                        <div className="flex gap-2">
                            <div className="flex-1 relative">
                                <Folder size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-[var(--text-muted)]" />
                                <input
                                    className="w-full pl-9 pr-3 py-3 text-sm rounded-xl border border-[var(--border)] bg-[var(--bg-secondary)] text-[var(--text-primary)] focus:outline-none focus:ring-2 focus:ring-[var(--accent)]/50 font-mono placeholder:font-sans"
                                    placeholder="/path/to/your/project"
                                    value={pathInput}
                                    onChange={(e) => setPathInput(e.target.value)}
                                    onKeyDown={(e) => {
                                        if (e.key === 'Enter' && pathInput.trim()) handleFinish();
                                    }}
                                />
                            </div>
                            <button
                                onClick={async () => {
                                    const picked = await chooseDir();
                                    if (picked) {
                                        setPathInput(picked);
                                    } else {
                                        if (!showBrowser) loadDirs('');
                                        setShowBrowser(!showBrowser);
                                    }
                                }}
                                className={`px-4 py-3 text-sm rounded-xl border transition-colors ${
                                    showBrowser
                                        ? 'border-[var(--accent)] text-[var(--accent)] bg-[var(--accent)]/10'
                                        : 'border-[var(--border)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:border-[var(--text-muted)]/40 bg-[var(--bg-secondary)]'
                                }`}
                            >
                                浏览
                            </button>
                        </div>
                    </div>

                    {/* Expandable directory browser */}
                    {showBrowser && (
                        <div className="w-full rounded-xl border border-[var(--border)] bg-[var(--bg-secondary)] overflow-hidden animate-fade-in mb-2">
                            {/* Breadcrumb path */}
                            <div className="flex items-center gap-1 px-3 py-2 border-b border-[var(--border)] bg-[var(--bg-primary)]/50">
                                {browsePath && browsePath !== '~' ? (
                                    <button
                                        type="button"
                                        onClick={goUp}
                                        className="flex items-center gap-1 text-xs text-[var(--text-muted)] hover:text-[var(--accent)] transition-colors"
                                    >
                                        <ChevronLeft size={12} />
                                        返回
                                    </button>
                                ) : null}
                                <span className="flex-1 text-xs text-[var(--text-muted)] truncate font-mono text-right">
                                    {browsePath}
                                </span>
                            </div>
                            <div className="max-h-52 overflow-y-auto">
                                {loadingDirs && (
                                    <div className="px-4 py-4 text-xs text-[var(--text-muted)] flex items-center justify-center gap-2">
                                        <Loader2 size={12} className="animate-spin" />
                                    </div>
                                )}
                                {dirError && (
                                    <div className="px-4 py-3 text-xs text-red-400 text-center">{dirError}</div>
                                )}
                                {!loadingDirs && !dirError && dirs.length === 0 && (
                                    <div className="px-4 py-3 text-xs text-[var(--text-muted)] text-center">空目录</div>
                                )}
                                {!loadingDirs && dirs.map((d) => (
                                    <button
                                        key={d.path}
                                        className="w-full flex items-center gap-2.5 px-4 py-2 text-sm text-left hover:bg-[var(--bg-hover)] transition-colors group"
                                        onClick={() => {
                                            setPathInput(d.path);
                                            loadDirs(d.path);
                                        }}
                                    >
                                        <Folder size={14} className="text-[var(--accent)] shrink-0" />
                                        <span className="flex-1 text-[var(--text-primary)] truncate">{d.name}</span>
                                        <ChevronRight size={12} className="text-[var(--text-muted)] opacity-0 group-hover:opacity-100 transition-opacity" />
                                    </button>
                                ))}
                            </div>
                        </div>
                    )}

                    {/* Open button */}
                    {pathInput.trim() && (
                        <button
                            onClick={() => handleFinish()}
                            disabled={creatingWorkspace}
                            className="w-full py-3 mt-2 text-sm font-medium text-white rounded-xl transition-colors disabled:opacity-50"
                            style={{ background: 'linear-gradient(135deg, #6366f1, #8b5cf6)' }}
                        >
                            {creatingWorkspace ? '创建中…' : `打开 ${pathInput.split('/').filter(Boolean).pop() || pathInput}`}
                        </button>
                    )}

                    {/* Navigation */}
                    <div className="flex justify-between items-center w-full mt-6">
                        <button
                            onClick={() => setStep(1)}
                            className="flex items-center gap-1 px-4 py-2 text-sm text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
                        >
                            <ChevronLeft size={16} />
                            上一步
                        </button>
                        <button
                            onClick={handleSkipProject}
                            className="flex items-center gap-1.5 px-4 py-2 text-sm text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                        >
                            <SkipForward size={14} />
                            稍后再选
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
}
