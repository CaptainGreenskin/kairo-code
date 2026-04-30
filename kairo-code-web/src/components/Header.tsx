import { Moon, Sun, Github, Settings, FolderTree, Search, HelpCircle, Download, Menu } from 'lucide-react';
import { useState, useEffect } from 'react';
import { formatTokenCount } from '@utils/tokenCount';
import { ModelSelector } from './ModelSelector';
import type { ConnectionStatus } from '@/types/agent';

interface HeaderProps {
    currentModel: string;
    tokenUsage: { input: number; output: number };
    estimatedCost: number;
    tokenLimit?: number;
    onToggleTheme: () => void;
    onOpenSettings: () => void;
    onToggleFileTree: () => void;
    fileTreeOpen: boolean;
    onOpenSearch: () => void;
    onOpenShortcuts?: () => void;
    onExport?: () => void;
    messagesCount?: number;
    searchActive?: boolean;
    tokenCount?: number;
    contextLimit?: number;
    models?: string[];
    onModelChange?: (model: string) => void;
    isThinking?: boolean;
    isMobile?: boolean;
    onMenuClick?: () => void;
    connectionStatus?: ConnectionStatus;
}

function getUsageColor(ratio: number): string {
    if (ratio > 0.85) return 'bg-[var(--color-danger)]';
    if (ratio > 0.70) return 'bg-[var(--color-warning)]';
    return 'bg-[var(--color-success)]';
}

const statusDotClass: Record<ConnectionStatus, string> = {
    connected: 'bg-green-500',
    connecting: 'bg-yellow-500 animate-pulse',
    disconnected: 'bg-gray-400',
    error: 'bg-red-500',
};

const statusLabelText: Record<ConnectionStatus, string> = {
    connected: 'Connected',
    connecting: 'Connecting…',
    disconnected: 'Disconnected',
    error: 'Connection error',
};

export function Header({
    currentModel,
    tokenUsage,
    estimatedCost,
    tokenLimit = 128000,
    onToggleTheme,
    onOpenSettings,
    onToggleFileTree,
    fileTreeOpen,
    onOpenSearch,
    onOpenShortcuts,
    onExport,
    messagesCount,
    searchActive,
    tokenCount,
    contextLimit,
    models,
    onModelChange,
    isThinking,
    isMobile,
    onMenuClick,
    connectionStatus,
}: HeaderProps) {
    const [isDark, setIsDark] = useState(() =>
        document.documentElement.classList.contains('dark'),
    );

    useEffect(() => {
        setIsDark(document.documentElement.classList.contains('dark'));
    }, []);

    const handleToggle = () => {
        const newIsDark = !isDark;
        document.documentElement.classList.toggle('dark');
        setIsDark(newIsDark);
        localStorage.setItem('kairo-theme', newIsDark ? 'dark' : 'light');
        onToggleTheme();
    };

    const totalTokens = tokenUsage.input + tokenUsage.output;
    const usageRatio = tokenLimit > 0 ? totalTokens / tokenLimit : 0;

    return (
        <header className="h-12 px-4 flex items-center justify-between border-b border-[var(--border)] bg-[var(--bg-secondary)] shrink-0">
            <div className="flex items-center gap-3">
                {isMobile && onMenuClick && (
                    <button
                        onClick={onMenuClick}
                        className="p-1.5 text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
                        aria-label="Open sidebar"
                    >
                        <Menu size={18} />
                    </button>
                )}
                <button
                    onClick={onToggleFileTree}
                    className={`p-1.5 rounded transition-colors ${
                        fileTreeOpen
                            ? 'bg-[var(--bg-hover)] text-[var(--text-primary)]'
                            : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)]'
                    }`}
                    aria-label="Toggle file tree"
                    title="Toggle file tree"
                >
                    <FolderTree size={18} />
                </button>
                <span className="font-semibold text-base text-[var(--text-primary)]">
                    kairo-code
                </span>
                {connectionStatus && (
                    <div
                        className="flex items-center gap-1.5 ml-2"
                        title={statusLabelText[connectionStatus]}
                    >
                        <span className={`w-2 h-2 rounded-full ${statusDotClass[connectionStatus]}`} />
                        {connectionStatus !== 'connected' && (
                            <span className="text-[10px] text-[var(--text-muted)]">
                                {statusLabelText[connectionStatus]}
                            </span>
                        )}
                    </div>
                )}
                {(models?.length ?? 0) > 0 && (
                    <ModelSelector
                        models={models!}
                        currentModel={currentModel || null}
                        onChange={onModelChange ?? (() => {})}
                        disabled={isThinking}
                    />
                )}
            </div>

            <div className="flex items-center gap-4">
                {totalTokens > 0 && (
                    <div className="hidden sm:flex flex-col gap-1 max-w-48">
                        <div className="flex items-center justify-between text-[10px] text-[var(--text-muted)]">
                            <span>
                                {totalTokens.toLocaleString()} / {tokenLimit.toLocaleString()}
                            </span>
                            <span className="font-medium text-[var(--color-success)]">
                                ${estimatedCost.toFixed(4)}
                            </span>
                        </div>
                        <div className="h-1 w-full bg-[var(--bg-hover)] rounded-full overflow-hidden">
                            <div
                                className={`h-full rounded-full transition-all ${getUsageColor(usageRatio)}`}
                                style={{ width: `${Math.min(usageRatio * 100, 100)}%` }}
                            />
                        </div>
                    </div>
                )}

                <button
                    onClick={onOpenSearch}
                    className={`p-1.5 rounded transition-colors ${
                        searchActive
                            ? 'text-[var(--accent)] bg-[var(--accent)]/10'
                            : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)]'
                    }`}
                    aria-label="Search messages"
                    title="Search messages (⌘F)"
                >
                    <Search size={16} />
                </button>

                {(tokenCount ?? 0) > 0 && (
                    <div
                        className="flex items-center gap-1 px-2 py-1 rounded text-xs text-[var(--text-secondary)] bg-[var(--bg-secondary)]"
                        title={`Estimated tokens in context${contextLimit ? ` (${Math.round((tokenCount! / contextLimit) * 100)}% of ${formatTokenCount(contextLimit)})` : ''}`}
                    >
                        <span className="font-mono">{formatTokenCount(tokenCount!)}</span>
                        <span>tokens</span>
                        {contextLimit && tokenCount! > contextLimit * 0.8 && (
                            <span className="text-amber-500 ml-0.5">⚠</span>
                        )}
                    </div>
                )}

                {(messagesCount ?? 0) > 0 && onExport && (
                    <button
                        onClick={onExport}
                        className="p-1.5 rounded text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
                        title="Export chat (Markdown)"
                        aria-label="Export chat (Markdown)"
                    >
                        <Download size={16} />
                    </button>
                )}

                <button
                    onClick={onOpenSettings}
                    className="text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
                    aria-label="Settings"
                >
                    <Settings size={18} />
                </button>

                <a
                    href="https://github.com/kairo-code/kairo-code"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
                    aria-label="GitHub"
                >
                    <Github size={18} />
                </a>

                {onOpenShortcuts && (
                    <button
                        onClick={onOpenShortcuts}
                        className="text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
                        aria-label="Keyboard shortcuts"
                        title="Keyboard shortcuts (?)"
                    >
                        <HelpCircle size={16} />
                    </button>
                )}

                <button
                    onClick={handleToggle}
                    className="text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
                    aria-label={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
                >
                    {isDark ? <Sun size={18} /> : <Moon size={18} />}
                </button>
            </div>
        </header>
    );
}
