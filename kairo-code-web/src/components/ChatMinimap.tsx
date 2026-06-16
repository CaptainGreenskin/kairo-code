import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { Message } from '@/types/agent';

const WIDTH = 80;

interface ChatMinimapProps {
    messages: Message[];
    scrollerRef: React.RefObject<HTMLElement | null>;
    onScrollToIndex: (index: number) => void;
}

export function ChatMinimap({ messages, scrollerRef, onScrollToIndex }: ChatMinimapProps) {
    const containerRef = useRef<HTMLDivElement>(null);
    const [viewportTop, setViewportTop] = useState(0);
    const [viewportHeight, setViewportHeight] = useState(0.3);
    const [dragging, setDragging] = useState(false);
    const dragStartRef = useRef({ y: 0, scrollTop: 0 });

    // Sync scroll position
    useEffect(() => {
        const el = scrollerRef.current;
        if (!el) return;
        const onScroll = () => {
            const ratio = el.scrollTop / Math.max(1, el.scrollHeight - el.clientHeight);
            const vpRatio = el.clientHeight / Math.max(1, el.scrollHeight);
            setViewportTop(ratio * (1 - vpRatio));
            setViewportHeight(vpRatio);
        };
        onScroll();
        el.addEventListener('scroll', onScroll, { passive: true });
        return () => el.removeEventListener('scroll', onScroll);
    }, [scrollerRef, messages.length]);

    // Drag viewport
    const handleDragStart = useCallback(
        (e: React.MouseEvent) => {
            e.preventDefault();
            e.stopPropagation();
            setDragging(true);
            const el = scrollerRef.current;
            dragStartRef.current = { y: e.clientY, scrollTop: el?.scrollTop ?? 0 };
        },
        [scrollerRef],
    );

    useEffect(() => {
        if (!dragging) return;
        const el = scrollerRef.current;
        const container = containerRef.current;
        if (!el || !container) return;

        const onMove = (e: MouseEvent) => {
            const dy = e.clientY - dragStartRef.current.y;
            const containerH = container.getBoundingClientRect().height;
            const scrollRange = el.scrollHeight - el.clientHeight;
            const scrollDelta = (dy / containerH) * scrollRange;
            el.scrollTo({ top: dragStartRef.current.scrollTop + scrollDelta });
        };
        const onUp = () => setDragging(false);

        window.addEventListener('mousemove', onMove);
        window.addEventListener('mouseup', onUp);
        return () => {
            window.removeEventListener('mousemove', onMove);
            window.removeEventListener('mouseup', onUp);
        };
    }, [dragging, scrollerRef]);

    const items = useMemo(() => {
        return messages.map((msg, i) => {
            const isUser = msg.role === 'user';
            const isError = msg.role === 'error';
            const preview = (msg.content ?? '').replace(/\n/g, ' ').slice(0, 12);
            const toolCount = msg.toolCalls?.length ?? 0;
            return { index: i, isUser, isError, preview, toolCount };
        });
    }, [messages]);

    if (messages.length < 5) return null;

    return (
        <div
            ref={containerRef}
            className="relative shrink-0 border-l border-[var(--border)] bg-[var(--bg-secondary)]/90 select-none overflow-hidden"
            style={{ width: WIDTH }}
        >
            {/* Message markers */}
            <div className="relative h-full overflow-y-hidden flex flex-col py-1 gap-[1px] z-10"
                 onClick={(e) => {
                     const rect = containerRef.current?.getBoundingClientRect();
                     if (!rect) return;
                     const ratio = (e.clientY - rect.top) / rect.height;
                     const idx = Math.round(ratio * (messages.length - 1));
                     onScrollToIndex(Math.max(0, Math.min(messages.length - 1, idx)));
                 }}>
                {items.map((item) => (
                    <div
                        key={item.index}
                        className="shrink-0 cursor-pointer hover:opacity-100 transition-opacity"
                        style={{ opacity: 0.8 }}
                        onClick={() => onScrollToIndex(item.index)}
                    >
                        {item.isUser ? (
                            <div className="flex items-center gap-1 px-1 py-[1px]" title={item.preview}>
                                <div className="w-1.5 h-1.5 rounded-full bg-purple-500 shrink-0" />
                                <span className="text-[8px] text-purple-400 truncate leading-tight">
                                    {item.preview || '…'}
                                </span>
                            </div>
                        ) : item.isError ? (
                            <div className="mx-1 h-[3px] rounded-sm bg-red-500/50" />
                        ) : (
                            <div className="flex items-center gap-0.5 px-1 py-[1px]" title={item.preview}>
                                <div
                                    className="h-[3px] rounded-sm bg-indigo-500/40 flex-1"
                                    style={{ minWidth: Math.min(60, 10 + (item.toolCount * 8)) }}
                                />
                                {item.toolCount > 0 && (
                                    <span className="text-[7px] text-orange-400/60">{item.toolCount}t</span>
                                )}
                            </div>
                        )}
                    </div>
                ))}
            </div>

            {/* Viewport indicator — z-20 to stay above content layer */}
            <div
                className="absolute left-0 right-0 z-20"
                style={{
                    top: `${viewportTop * 100}%`,
                    height: `${Math.max(viewportHeight * 100, 10)}%`,
                    background: 'var(--accent)',
                    opacity: dragging ? 0.25 : 0.12,
                    borderTop: '2px solid var(--accent)',
                    borderBottom: '2px solid var(--accent)',
                    cursor: dragging ? 'grabbing' : 'grab',
                    transition: dragging ? 'none' : 'top 0.1s ease-out',
                }}
                onMouseDown={handleDragStart}
            />
        </div>
    );
}
