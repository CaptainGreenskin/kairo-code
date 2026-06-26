import { Menu, Square, Loader2, AlertTriangle, CheckCircle, Wifi, WifiOff } from 'lucide-react';

interface MobileStatusBarProps {
    isThinking: boolean;
    currentToolName: string | undefined;
    pendingApprovalCount: number;
    isConnected: boolean;
    running: boolean;
    onMenuClick: () => void;
    onStop: () => void;
}

type AgentStatus = 'idle' | 'thinking' | 'running' | 'approval' | 'done' | 'disconnected';

function deriveStatus(props: MobileStatusBarProps): AgentStatus {
    if (!props.isConnected) return 'disconnected';
    if (props.pendingApprovalCount > 0) return 'approval';
    if (props.isThinking) return 'thinking';
    if (props.running) return 'running';
    return 'idle';
}

const STATUS_CONFIG: Record<AgentStatus, { label: string; color: string; icon: React.ReactNode }> = {
    idle: {
        label: 'Idle',
        color: 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30',
        icon: <CheckCircle size={12} />,
    },
    thinking: {
        label: 'Thinking...',
        color: 'bg-blue-500/15 text-blue-400 border-blue-500/30',
        icon: <Loader2 size={12} className="animate-spin" />,
    },
    running: {
        label: 'Running',
        color: 'bg-blue-500/15 text-blue-400 border-blue-500/30',
        icon: <Loader2 size={12} className="animate-spin" />,
    },
    approval: {
        label: 'Waiting approval',
        color: 'bg-amber-500/15 text-amber-400 border-amber-500/30',
        icon: <AlertTriangle size={12} className="animate-pulse" />,
    },
    done: {
        label: 'Done',
        color: 'bg-[var(--bg-hover)] text-[var(--text-muted)] border-[var(--border)]',
        icon: <CheckCircle size={12} />,
    },
    disconnected: {
        label: 'Offline',
        color: 'bg-red-500/15 text-red-400 border-red-500/30',
        icon: <WifiOff size={12} />,
    },
};

/**
 * Compact mobile status header (44px).
 * Shows: hamburger menu | status pill | stop button (when running).
 */
export function MobileStatusBar({
    isThinking,
    currentToolName,
    pendingApprovalCount,
    isConnected,
    running,
    onMenuClick,
    onStop,
}: MobileStatusBarProps) {
    const status = deriveStatus({ isThinking, currentToolName, pendingApprovalCount, isConnected, running, onMenuClick, onStop });
    const cfg = STATUS_CONFIG[status];

    const showStop = running || isThinking;

    return (
        <div className="h-[44px] min-h-[44px] flex items-center px-3 gap-2 bg-[var(--bg-secondary)] border-b border-[var(--border)]">
            {/* Left: hamburger */}
            <button
                onClick={onMenuClick}
                className="p-2 -ml-1 rounded-lg text-[var(--text-secondary)] active:bg-[var(--bg-hover)]"
                aria-label="Menu"
            >
                <Menu size={20} />
            </button>

            {/* Center: status pill */}
            <div className="flex-1 flex items-center justify-center">
                <div className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium border ${cfg.color}`}>
                    {cfg.icon}
                    <span>{cfg.label}</span>
                    {status === 'running' && currentToolName && (
                        <span className="opacity-70 truncate max-w-[100px]">
                            {currentToolName}
                        </span>
                    )}
                    {status === 'approval' && pendingApprovalCount > 1 && (
                        <span className="ml-0.5 bg-amber-500/20 text-amber-300 px-1.5 py-0.5 rounded-full text-[10px] font-bold">
                            {pendingApprovalCount}
                        </span>
                    )}
                </div>
            </div>

            {/* Right: stop button or connection indicator */}
            {showStop ? (
                <button
                    onClick={onStop}
                    className="p-2 -mr-1 rounded-lg text-red-400 active:bg-red-500/20"
                    aria-label="Stop"
                >
                    <Square size={18} fill="currentColor" />
                </button>
            ) : (
                <div className="p-2 -mr-1">
                    {isConnected ? (
                        <Wifi size={14} className="text-emerald-500/60" />
                    ) : (
                        <WifiOff size={14} className="text-red-400/60" />
                    )}
                </div>
            )}
        </div>
    );
}
