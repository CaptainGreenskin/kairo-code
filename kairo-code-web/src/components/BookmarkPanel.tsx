import { X, Star, ArrowRight } from 'lucide-react';
import type { Message } from '@/types/agent';
import { getBookmarks } from '@utils/bookmarkMessages';

interface BookmarkPanelProps {
    sessionId: string;
    messages: Message[];
    isOpen: boolean;
    onClose: () => void;
    onScrollToMessage: (messageId: string) => void;
}

export function BookmarkPanel({ sessionId, messages, isOpen, onClose, onScrollToMessage }: BookmarkPanelProps) {
    if (!isOpen) return null;

    const bookmarkedIds = new Set(getBookmarks(sessionId));
    const bookmarkedMessages = messages.filter(m => bookmarkedIds.has(m.id));

    return (
        <div className="flex flex-col border-l border-[var(--border)] bg-[var(--bg-primary)] w-72 flex-shrink-0 h-full">
            {/* Header */}
            <div className="flex items-center justify-between px-3 py-2.5 border-b border-[var(--border)]">
                <div className="flex items-center gap-1.5 text-sm font-medium text-[var(--text-primary)]">
                    <Star size={13} className="text-amber-400" fill="currentColor" />
                    Bookmarks
                    {bookmarkedMessages.length > 0 && (
                        <span className="text-[11px] text-[var(--text-muted)] font-normal ml-1">
                            ({bookmarkedMessages.length})
                        </span>
                    )}
                </div>
                <button onClick={onClose} className="p-0.5 rounded hover:bg-[var(--bg-secondary)] transition-colors">
                    <X size={14} className="text-[var(--text-muted)]" />
                </button>
            </div>

            {/* List */}
            <div className="flex-1 overflow-y-auto py-2">
                {bookmarkedMessages.length === 0 ? (
                    <div className="px-3 py-8 text-center text-xs text-[var(--text-muted)]">
                        <Star size={20} className="mx-auto mb-2 opacity-30" />
                        No bookmarks yet.<br />
                        Click ★ on any message to bookmark it.
                    </div>
                ) : (
                    bookmarkedMessages.map(msg => (
                        <button
                            key={msg.id}
                            onClick={() => onScrollToMessage(msg.id)}
                            className="w-full flex items-start gap-2 px-3 py-2.5 hover:bg-[var(--bg-secondary)] transition-colors text-left group"
                        >
                            <div className="flex-1 min-w-0">
                                <div className="text-[10px] text-[var(--text-muted)] mb-0.5 capitalize">
                                    {msg.role}
                                </div>
                                <div className="text-xs text-[var(--text-secondary)] leading-snug line-clamp-3">
                                    {(msg.content ?? '').slice(0, 150) || '(tool call)'}
                                </div>
                            </div>
                            <ArrowRight size={12} className="text-[var(--text-muted)] flex-shrink-0 mt-3 opacity-0 group-hover:opacity-100 transition-opacity" />
                        </button>
                    ))
                )}
            </div>
        </div>
    );
}
