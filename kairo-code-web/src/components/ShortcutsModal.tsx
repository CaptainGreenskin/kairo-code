import { X } from 'lucide-react';

interface ShortcutsModalProps {
    isOpen: boolean;
    onClose: () => void;
}

interface ShortcutEntry {
    keys: string[];
    description: string;
}

interface ShortcutGroup {
    title: string;
    entries: ShortcutEntry[];
}

const SHORTCUT_GROUPS: ShortcutGroup[] = [
    {
        title: 'Navigation',
        entries: [
            { keys: ['⌘', 'K'], description: 'Open command palette' },
            { keys: ['⌘', '⇧', 'F'], description: 'Search workspace' },
            { keys: ['⌘', 'N'], description: 'New session' },
            { keys: ['⌘', 'W'], description: 'Close session' },
            { keys: ['⌘', '1-9'], description: 'Switch to session N' },
            { keys: ['Esc'], description: 'Close modal / cancel' },
        ],
    },
    {
        title: 'Chat',
        entries: [
            { keys: ['⌘', '↵'], description: 'Send message' },
            { keys: ['⌘', '⇧', 'C'], description: 'Copy conversation as Markdown' },
            { keys: ['?'], description: 'Show shortcuts' },
        ],
    },
    {
        title: 'Tool Approval',
        entries: [
            { keys: ['y'], description: 'Approve pending tool call' },
            { keys: ['n'], description: 'Reject pending tool call' },
        ],
    },
    {
        title: 'File',
        entries: [
            { keys: ['@'], description: 'Open file picker in chat' },
        ],
    },
];

function KeyBadge({ char }: { char: string }) {
    return (
        <kbd className="px-1.5 py-0.5 text-xs font-mono bg-[var(--bg-tertiary)] border border-[var(--border)] rounded text-[var(--text-secondary)]">
            {char}
        </kbd>
    );
}

export function ShortcutsModal({ isOpen, onClose }: ShortcutsModalProps) {
    if (!isOpen) return null;

    return (
        <div
            className="fixed inset-0 z-50 bg-black/50 flex items-center justify-center"
            onClick={onClose}
        >
            <div
                className="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl shadow-2xl w-[480px] max-h-[80vh] overflow-y-auto"
                onClick={e => e.stopPropagation()}
            >
                {/* Header */}
                <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--border)]">
                    <h2 className="text-sm font-semibold text-[var(--text-primary)]">
                        Keyboard Shortcuts
                    </h2>
                    <button
                        onClick={onClose}
                        className="p-1 rounded text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
                        aria-label="Close"
                    >
                        <X size={16} />
                    </button>
                </div>

                {/* Groups */}
                <div className="px-4 py-3">
                    {SHORTCUT_GROUPS.map((group) => (
                        <div key={group.title} className="mb-4 last:mb-0">
                            <h3 className="text-xs font-semibold text-[var(--text-muted)] uppercase tracking-wider mb-2">
                                {group.title}
                            </h3>
                            <div>
                                {group.entries.map((entry, i) => (
                                    <div key={i} className="flex items-center gap-3 py-1.5">
                                        <div className="flex gap-1 min-w-[80px]">
                                            {entry.keys.map((k, j) => (
                                                <KeyBadge key={j} char={k} />
                                            ))}
                                        </div>
                                        <span className="text-sm text-[var(--text-secondary)]">
                                            {entry.description}
                                        </span>
                                    </div>
                                ))}
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        </div>
    );
}
