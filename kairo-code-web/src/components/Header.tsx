import { Moon, Sun, Github, Settings, FolderTree, Search, HelpCircle, Menu, Star } from 'lucide-react';
import React, { useState, useEffect } from 'react';
import { ModelSelector } from './ModelSelector';
import { StatsPopover } from './StatsPopover';
import { TokenUsageBar } from './TokenUsageBar';
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
    exportAction?: React.ReactNode;
    searchActive?: boolean;
    sessionStats?: {
        userMessages: number;
        assistantMessages: number;
        toolCalls: number;
        estimatedTokens: number;
    };
    models?: string[];
    onModelChange?: (model: string) => void;
    isThinking?: boolean;
    isMobile?: boolean;
    onMenuClick?: () => void;
    connectionStatus?: ConnectionStatus;
    bookmarksActive?: boolean;
    onToggleBookmarks?: () => void;
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

export const Header = React.memo(function Header({
    currentModel,
    tokenUsage,
    estimatedCost,
    tokenLimit: _tokenLimit = 128000,
    onToggleTheme,
    onOpenSettings,
    onToggleFileTree,
    fileTreeOpen,
    onOpenSearch,
    onOpenShortcuts,
    exportAction,
    searchActive,
    sessionStats,
    models,
    onModelChange,
    isThinking,
    isMobile,
    onMenuClick,
    connectionStatus,
    bookmarksActive,
    onToggleBookmarks,
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
                <TokenUsageBar
                    inputTokens={tokenUsage.input}
                    outputTokens={tokenUsage.output}
                    estimatedCost={estimatedCost}
                    model={currentModel ?? ''}
                />

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

                {onToggleBookmarks && (
                    <button
                        onClick={onToggleBookmarks}
                        className={`p-1.5 rounded transition-colors ${
                            bookmarksActive
                                ? 'text-amber-400 bg-amber-400/10'
                                : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)]'
                        }`}
                        aria-label="Toggle bookmarks"
                        title="Bookmarks"
                    >
                        <Star size={16} fill={bookmarksActive ? 'currentColor' : 'none'} />
                    </button>
                )}

                {sessionStats && <StatsPopover stats={sessionStats} />}

                {exportAction}

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
});
