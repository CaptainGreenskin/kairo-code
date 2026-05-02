import { Code2, GitBranch, Search, Wrench, Bug, FileText, Clock } from 'lucide-react';

interface QuickPrompt {
    icon: React.ReactNode;
    label: string;
    prompt: string;
}

const QUICK_PROMPTS: QuickPrompt[] = [
    {
        icon: <FileText size={14} />,
        label: 'Explore project structure',
        prompt: 'List the main directories and files in this project. Briefly explain what each one does.',
    },
    {
        icon: <Code2 size={14} />,
        label: 'Explain entry point',
        prompt: 'Find and explain the main entry point of this project. How does it start?',
    },
    {
        icon: <GitBranch size={14} />,
        label: 'Recent changes',
        prompt: 'Show me the recent git commits and summarize what changed.',
    },
    {
        icon: <Search size={14} />,
        label: 'Find TODOs',
        prompt: 'Search for TODO, FIXME, and HACK comments in the codebase. List them with file locations.',
    },
    {
        icon: <Bug size={14} />,
        label: 'Run tests',
        prompt: 'Run the test suite and report the results. If any tests fail, explain what is wrong.',
    },
    {
        icon: <Wrench size={14} />,
        label: 'Check dependencies',
        prompt: 'List the project dependencies and check if any are outdated or have known vulnerabilities.',
    },
];

export interface RecentSession {
    id: string;
    name: string;
    lastMessage?: string;
    updatedAt?: number;
}

interface WelcomeScreenProps {
    onSelectPrompt: (prompt: string) => void;
    appVersion?: string;
    recentSessions?: RecentSession[];
    onSelectSession?: (id: string) => void;
}

function formatRelativeTime(timestamp: number): string {
    const now = Date.now();
    const diffMs = now - timestamp;
    const diffSec = Math.floor(diffMs / 1000);
    const diffMin = Math.floor(diffSec / 60);
    const diffHr = Math.floor(diffMin / 60);
    const diffDay = Math.floor(diffHr / 24);

    if (diffSec < 60) return 'just now';
    if (diffMin < 60) return `${diffMin} ${diffMin === 1 ? 'minute' : 'minutes'} ago`;
    if (diffHr < 24) return `${diffHr} ${diffHr === 1 ? 'hour' : 'hours'} ago`;
    if (diffDay < 7) return `${diffDay} ${diffDay === 1 ? 'day' : 'days'} ago`;
    return new Date(timestamp).toLocaleDateString();
}

export function WelcomeScreen({ onSelectPrompt, appVersion, recentSessions, onSelectSession }: WelcomeScreenProps) {
    const showRecentSessions = recentSessions && recentSessions.length > 0;

    return (
        <div className="flex flex-col items-center justify-center h-full px-6 py-12 select-none">
            {/* Brand */}
            <div className="flex items-center gap-2.5 mb-2">
                <div className="w-8 h-8 rounded-lg bg-[var(--accent)] flex items-center justify-center flex-shrink-0">
                    <Code2 size={16} className="text-white" />
                </div>
                <span className="text-xl font-semibold text-[var(--text-primary)] tracking-tight">Kairo Code</span>
            </div>
            {appVersion && (
                <span className="text-[11px] text-[var(--text-muted)] font-mono mb-8">v{appVersion}</span>
            )}
            {!appVersion && <div className="mb-8" />}

            {/* Tagline */}
            <p className="text-sm text-[var(--text-secondary)] mb-8 text-center max-w-xs leading-relaxed">
                AI coding agent in your browser.{' '}
                <span className="text-[var(--text-muted)]">Ask anything about your codebase.</span>
            </p>

            {/* Recent sessions */}
            {showRecentSessions && (
                <div className="w-full max-w-lg mb-6">
                    <div className="flex items-center gap-2 mb-2">
                        <Clock size={13} className="text-[var(--text-muted)]" />
                        <span className="text-xs font-medium text-[var(--text-secondary)]">Recent sessions</span>
                    </div>
                    <div className="flex flex-col gap-1.5">
                        {recentSessions.slice(0, 5).map((session) => (
                            <button
                                key={session.id}
                                onClick={() => onSelectSession?.(session.id)}
                                className="group w-full flex flex-col items-start p-2.5 rounded-lg border border-[var(--border)]
                                    bg-[var(--bg-secondary)] hover:bg-[var(--bg-tertiary)]
                                    hover:border-[var(--accent)]/40 transition-all text-left"
                            >
                                <div className="flex items-center justify-between w-full">
                                    <span className="text-sm font-medium text-[var(--text-primary)] group-hover:text-[var(--accent)] transition-colors truncate">
                                        {session.name}
                                    </span>
                                    {session.updatedAt && (
                                        <span className="text-[10px] text-[var(--text-muted)] flex-shrink-0 ml-2">
                                            {formatRelativeTime(session.updatedAt)}
                                        </span>
                                    )}
                                </div>
                                {session.lastMessage && (
                                    <span className="text-[11px] text-[var(--text-muted)] truncate mt-0.5 w-full leading-snug">
                                        {session.lastMessage.length > 80
                                            ? session.lastMessage.slice(0, 80) + '…'
                                            : session.lastMessage}
                                    </span>
                                )}
                            </button>
                        ))}
                    </div>
                </div>
            )}

            {/* Quick prompts grid */}
            <div className="w-full max-w-lg grid grid-cols-2 gap-2">
                {QUICK_PROMPTS.map((qp) => (
                    <button
                        key={qp.label}
                        onClick={() => onSelectPrompt(qp.prompt)}
                        className="group flex items-start gap-2.5 p-3 rounded-lg border border-[var(--border)]
                            bg-[var(--bg-secondary)] hover:bg-[var(--bg-tertiary)]
                            hover:border-[var(--accent)]/40 transition-all text-left"
                    >
                        <span className="text-[var(--text-muted)] group-hover:text-[var(--accent)] transition-colors mt-0.5 flex-shrink-0">
                            {qp.icon}
                        </span>
                        <span className="text-xs text-[var(--text-secondary)] group-hover:text-[var(--text-primary)] transition-colors leading-snug">
                            {qp.label}
                        </span>
                    </button>
                ))}
            </div>

            {/* Hint */}
            <p className="text-[11px] text-[var(--text-muted)] mt-8">
                Press{' '}
                <kbd className="px-1 py-0.5 rounded border border-[var(--border)] bg-[var(--bg-tertiary)] font-mono text-[10px]">&#8984;K</kbd>
                {' '}for commands
            </p>
        </div>
    );
}
