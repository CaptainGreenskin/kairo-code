import { Moon, Sun, Github } from 'lucide-react';
import { useState, useEffect } from 'react';

interface HeaderProps {
    currentModel: string;
    tokenUsage: { input: number; output: number };
    estimatedCost: number;
    onToggleTheme: () => void;
}

export function Header({
    currentModel,
    tokenUsage,
    estimatedCost,
    onToggleTheme,
}: HeaderProps) {
    const [isDark, setIsDark] = useState(() =>
        document.documentElement.classList.contains('dark'),
    );

    useEffect(() => {
        setIsDark(document.documentElement.classList.contains('dark'));
    }, []);

    const handleToggle = () => {
        document.documentElement.classList.toggle('dark');
        setIsDark((prev) => !prev);
        onToggleTheme();
    };

    return (
        <header className="h-12 px-4 flex items-center justify-between border-b border-[var(--border)] bg-[var(--bg-secondary)] shrink-0">
            <div className="flex items-center gap-3">
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
                {tokenUsage.input > 0 && (
                    <div className="hidden sm:flex items-center gap-2 text-xs text-[var(--text-secondary)]">
                        <span>
                            In: {tokenUsage.input.toLocaleString()}
                        </span>
                        <span>
                            Out: {tokenUsage.output.toLocaleString()}
                        </span>
                        <span className="font-medium text-[var(--color-success)]">
                            ${estimatedCost.toFixed(4)}
                        </span>
                    </div>
                )}

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
