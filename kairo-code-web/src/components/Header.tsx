import { Menu, PanelLeft, PanelBottom, PanelRight } from 'lucide-react';
import React from 'react';
import { StatsPopover } from './StatsPopover';
import { TokenUsageBar } from './TokenUsageBar';
import { useLayoutStore } from '@store/layoutStore';
import type { ConnectionStatus } from '@/types/agent';

interface HeaderProps {
    currentModel: string;
    tokenUsage: { input: number; output: number };
    estimatedCost: number;
    tokenLimit?: number;
    /** Context window size for the active model. Currently unused — kept for prop compatibility. */
    contextWindow?: number;
    /** True briefly after a CONTEXT_COMPACTED event. Currently unused. */
    isCompacting?: boolean;
    exportAction?: React.ReactNode;
    sessionStats?: {
        userMessages: number;
        assistantMessages: number;
        toolCalls: number;
        estimatedTokens: number;
    };
    isThinking?: boolean;
    isToolRunning?: boolean;
    isMobile?: boolean;
    onMenuClick?: () => void;
    connectionStatus?: ConnectionStatus;
    fileTrackerSlot?: React.ReactNode;
    /** Optional element rendered after the kairo-code title (used for the workspace switcher). */
    leadingSlot?: React.ReactNode;
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
    contextWindow: _contextWindow,
    isCompacting: _isCompacting = false,
    exportAction,
    sessionStats,
    isThinking,
    isToolRunning = false,
    isMobile,
    onMenuClick,
    connectionStatus,
    fileTrackerSlot,
    leadingSlot,
}: HeaderProps) {
    const primarySidebarOpen = useLayoutStore((s) => s.primarySidebarOpen);
    const bottomPanelOpen = useLayoutStore((s) => s.bottomPanelOpen);
    const chatSidebarOpen = useLayoutStore((s) => s.chatSidebarOpen);
    const togglePrimarySidebar = useLayoutStore((s) => s.togglePrimarySidebar);
    const toggleBottomPanel = useLayoutStore((s) => s.toggleBottomPanel);
    const toggleChatSidebar = useLayoutStore((s) => s.toggleChatSidebar);

    // Derive status bar state: connecting > thinking > tool > idle
    const statusBarState =
        connectionStatus === 'connecting'
            ? 'connecting'
            : isThinking
              ? 'thinking'
              : isToolRunning
                ? 'tool'
                : 'idle';

    const toggleClass = (active: boolean) =>
        `p-1.5 rounded transition-colors ${
            active
                ? 'bg-[var(--bg-hover)] text-[var(--text-primary)]'
                : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)]'
        }`;

    return (
        <header className="h-12 px-4 flex items-center justify-between border-b border-[var(--border)] bg-[var(--bg-secondary)] shrink-0 relative">
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
                <span className="flex items-center gap-1.5 font-semibold text-base text-[var(--text-primary)]">
                    <img src="/logo.png" alt="kairo-code" className="w-5 h-5 rounded-sm object-contain" />
                    kairo-code
                </span>
                {leadingSlot}
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
            </div>

            <div className="flex items-center gap-3">
                <TokenUsageBar
                    inputTokens={tokenUsage.input}
                    outputTokens={tokenUsage.output}
                    estimatedCost={estimatedCost}
                    model={currentModel ?? ''}
                />
                {sessionStats && <StatsPopover stats={sessionStats} />}
                {fileTrackerSlot}
                {exportAction}

                {/* Panel toggles — VS Code-style */}
                <div className="flex items-center gap-1 ml-1 pl-3 border-l border-[var(--border)]">
                    <button
                        onClick={togglePrimarySidebar}
                        className={toggleClass(primarySidebarOpen)}
                        aria-label="Toggle primary sidebar"
                        aria-pressed={primarySidebarOpen}
                        title="Toggle primary sidebar (⌘B)"
                    >
                        <PanelLeft size={16} />
                    </button>
                    <button
                        onClick={toggleBottomPanel}
                        className={toggleClass(bottomPanelOpen)}
                        aria-label="Toggle bottom panel"
                        aria-pressed={bottomPanelOpen}
                        title="Toggle terminal (Ctrl+`)"
                    >
                        <PanelBottom size={16} />
                    </button>
                    <button
                        onClick={toggleChatSidebar}
                        className={toggleClass(chatSidebarOpen)}
                        aria-label="Toggle chat sidebar"
                        aria-pressed={chatSidebarOpen}
                        title="Toggle chat sidebar"
                    >
                        <PanelRight size={16} />
                    </button>
                </div>
            </div>

            {/* Status progress bar — 2px strip at the bottom of the header */}
            {statusBarState !== 'idle' && (
                <div
                    className={`absolute bottom-0 left-0 right-0 h-[2px] ${
                        statusBarState === 'connecting'
                            ? 'bg-blue-500 animate-pulse'
                            : statusBarState === 'thinking'
                              ? 'bg-green-500 status-bar-flow'
                              : 'bg-amber-500'
                    }`}
                    aria-hidden="true"
                />
            )}
        </header>
    );
});

