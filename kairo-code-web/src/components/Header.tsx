import { Moon, Sun, Github, Settings, FolderTree } from 'lucide-react';
import { useState, useEffect } from 'react';

interface HeaderProps {
    currentModel: string;
    tokenUsage: { input: number; output: number };
    estimatedCost: number;
    tokenLimit?: number;
    onToggleTheme: () => void;
    onOpenSettings: () => void;
    onToggleFileTree: () => void;
    fileTreeOpen: boolean;
}

function getUsageColor(ratio: number): string {
    if (ratio > 0.85) return 'bg-[var(--color-danger)]';
    if (ratio > 0.70) return 'bg-[var(--color-warning)]';
    return 'bg-[var(--color-success)]';
}

export function Header({
    currentModel,
    tokenUsage,
    estimatedCost,
    tokenLimit = 128000,
    onToggleTheme,
    onOpenSettings,
    onToggleFileTree,
    fileTreeOpen,
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
                {currentModel && (
                    <span className="text-xs px-2 py-0.5 rounded bg-[var(--color-info-bg)] text-[var(--color-info)] font-mono">
                        {currentModel}
                    </span>
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
