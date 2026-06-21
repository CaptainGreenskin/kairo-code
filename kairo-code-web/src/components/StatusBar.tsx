import { useEffect, useState } from 'react';
import { Settings, Github, FileText, HelpCircle, Moon, Sun, Terminal, Bookmark, Clock, Download, Plug, LogOut, QrCode } from 'lucide-react';
import type { ConnectionStatus } from '@/types/agent';
import { useAuthStore } from '@store/authStore';
import { getAuthToken } from '@/api/auth';
import { QRCodeSVG } from 'qrcode.react';

interface StatusBarProps {
    connectionStatus?: ConnectionStatus;
    currentModel?: string;
    onOpenSettings: () => void;
    onOpenMemory?: () => void;
    onOpenShortcuts?: () => void;
    onToggleTheme: () => void;
    onOpenShell?: () => void;
    onOpenBookmarks?: () => void;
    onOpenTimeline?: () => void;
    onExport?: () => void;
    onOpenMcp?: () => void;
    evolvedSkillCount?: number;
    evolutionReviewing?: boolean;
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
    onOpenShell,
    evolvedSkillCount,
    evolutionReviewing,
    onOpenBookmarks,
    onOpenTimeline,
    onExport,
    onOpenMcp,
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
                {evolutionReviewing && (
                    <span className="flex items-center gap-1 text-purple-400 animate-pulse" title="自进化回顾：分析本次对话经验，提炼可复用技能">
                        <span>🧬</span>
                        <span>经验提炼中...</span>
                    </span>
                )}
                {!evolutionReviewing && evolvedSkillCount != null && evolvedSkillCount > 0 && (
                    <span className="flex items-center gap-1 text-purple-400" title={`${evolvedSkillCount} evolved skill${evolvedSkillCount > 1 ? 's' : ''} active`}>
                        <span>✨</span>
                        <span>{evolvedSkillCount} skill{evolvedSkillCount > 1 ? 's' : ''}</span>
                    </span>
                )}
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
                {onOpenShell && (
                    <button onClick={onOpenShell} className="p-1 rounded hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)] transition-colors" title="Shell Terminal" aria-label="Shell">
                        <Terminal size={13} />
                    </button>
                )}
                {onOpenBookmarks && (
                    <button onClick={onOpenBookmarks} className="p-1 rounded hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)] transition-colors" title="Bookmarks" aria-label="Bookmarks">
                        <Bookmark size={13} />
                    </button>
                )}
                {onOpenTimeline && (
                    <button onClick={onOpenTimeline} className="p-1 rounded hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)] transition-colors" title="Execution Timeline" aria-label="Timeline">
                        <Clock size={13} />
                    </button>
                )}
                {onExport && (
                    <button onClick={onExport} className="p-1 rounded hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)] transition-colors" title="Export Chat" aria-label="Export">
                        <Download size={13} />
                    </button>
                )}
                {onOpenMcp && (
                    <button onClick={onOpenMcp} className="p-1 rounded hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)] transition-colors" title="MCP Servers" aria-label="MCP">
                        <Plug size={13} />
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
                {useAuthStore.getState().isAuthenticated && (
                    <>
                        <ShareQrButton />
                        <button
                            onClick={() => useAuthStore.getState().logout()}
                            className="p-1 rounded hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)] transition-colors"
                            title={`Logout (${useAuthStore.getState().user?.username ?? ''})`}
                            aria-label="Logout"
                        >
                            <LogOut size={13} />
                        </button>
                    </>
                )}
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

function ShareQrButton() {
    const [show, setShow] = useState(false);
    const [qrUrl, setQrUrl] = useState('');

    const handleClick = async () => {
        if (show) { setShow(false); return; }
        const token = getAuthToken();
        try {
            const res = await fetch('/api/server-info');
            const info = await res.json();
            const base = info.lanUrl || window.location.origin;
            setQrUrl(token ? `${base}?token=${token}` : base);
        } catch {
            setQrUrl(`${window.location.origin}?token=${token}`);
        }
        setShow(true);
    };

    return (
        <>
            <button
                onClick={handleClick}
                className="p-1 rounded hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)] transition-colors"
                title="Share to mobile (QR code)"
                aria-label="Share QR"
            >
                <QrCode size={13} />
            </button>
            {show && (
                <div className="fixed inset-0 z-50 flex items-center justify-center"
                     style={{ background: 'rgba(0,0,0,0.5)' }}
                     onClick={() => setShow(false)}>
                    <div className="p-6 rounded-lg text-center"
                         style={{ background: 'var(--bg-secondary)', border: '1px solid var(--border-color)' }}
                         onClick={e => e.stopPropagation()}>
                        <p className="text-sm font-medium mb-3" style={{ color: 'var(--text-primary)' }}>
                            Scan to access on mobile
                        </p>
                        <div className="p-3 rounded-lg inline-block" style={{ background: '#fff' }}>
                            <QRCodeSVG value={qrUrl} size={180} />
                        </div>
                        <p className="text-[10px] mt-2" style={{ color: 'var(--text-secondary)' }}>
                            Auto-login · no password needed
                        </p>
                    </div>
                </div>
            )}
        </>
    );
}
