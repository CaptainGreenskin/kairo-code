import { useEffect, useState } from 'react';
import { Settings, Github, FileText, HelpCircle, Moon, Sun } from 'lucide-react';
import type { ConnectionStatus } from '@/types/agent';

interface StatusBarProps {
    connectionStatus?: ConnectionStatus;
    currentModel?: string;
    onOpenSettings: () => void;
    onOpenMemory?: () => void;
    onOpenShortcuts?: () => void;
    onToggleTheme: () => void;
}

const statusDotClass: Record<ConnectionStatus, string> = {
    connected: 'bg-green-500',
    connecting: 'bg-yellow-500 animate-pulse',
    disconnected: 'bg-gray-400',
    error: 'bg-red-500',
};

const statusLabel: Record<ConnectionStatus, string> = {
    connected: 'Connected',
    connecting: 'Connecting…',
    disconnected: 'Disconnected',
    error: 'Connection error',
};

export function StatusBar({
    connectionStatus,
    currentModel,
    onOpenSettings,
    onOpenMemory,
    onOpenShortcuts,
    onToggleTheme,
}: StatusBarProps) {
    const [isDark, setIsDark] = useState(() =>
        document.documentElement.classList.contains('dark'),
    );

    useEffect(() => {
        setIsDark(document.documentElement.classList.contains('dark'));
    }, []);

    const handleTheme = () => {
        const next = !isDark;
        document.documentElement.classList.toggle('dark');
        setIsDark(next);
        localStorage.setItem('kairo-theme', next ? 'dark' : 'light');
        onToggleTheme();
    };

    return (
        <footer
            className="h-6 px-2 flex items-center justify-between border-t border-[var(--border)] bg-[var(--bg-secondary)] text-[11px] text-[var(--text-muted)] shrink-0"
            aria-label="Status Bar"
        >
            <div className="flex items-center gap-3">
                {connectionStatus && (
                    <span className="flex items-center gap-1.5" title={statusLabel[connectionStatus]}>
                        <span className={`w-1.5 h-1.5 rounded-full ${statusDotClass[connectionStatus]}`} />
                        <span>{statusLabel[connectionStatus]}</span>
                    </span>
                )}
                {currentModel && <span className="font-mono">{currentModel}</span>}
            </div>
            <div className="flex items-center gap-2">
                {onOpenMemory && (
                    <button
                        onClick={onOpenMemory}
                        className="p-1 rounded hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)] transition-colors"
                        title="Memory (CLAUDE.md)"
                        aria-label="Memory"
                    >
                        <FileText size={13} />
                    </button>
                )}
                <button
                    onClick={onOpenSettings}
                    className="p-1 rounded hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)] transition-colors"
                    title="Settings"
                    aria-label="Settings"
                >
                    <Settings size={13} />
                </button>
                <a
                    href="https://github.com/CaptainGreenskin/kairo-code"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="p-1 rounded hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)] transition-colors"
                    title="GitHub"
                    aria-label="GitHub"
                >
                    <Github size={13} />
                </a>
                <button
                    onClick={handleTheme}
                    className="p-1 rounded hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)] transition-colors"
                    title={isDark ? 'Switch to light' : 'Switch to dark'}
                    aria-label="Toggle theme"
                >
                    {isDark ? <Sun size={13} /> : <Moon size={13} />}
                </button>
                {onOpenShortcuts && (
                    <button
                        onClick={onOpenShortcuts}
                        className="p-1 rounded hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)] transition-colors"
                        title="Keyboard shortcuts (?)"
                        aria-label="Shortcuts"
                    >
                        <HelpCircle size={13} />
                    </button>
                )}
            </div>
        </footer>
    );
}
