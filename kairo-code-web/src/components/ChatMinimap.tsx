import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { Message } from '@/types/agent';

interface MinimapBlock {
    index: number;
    color: string;
    height: number;
    y: number;
    preview: string;
}

const COLORS: Record<string, string> = {
    user: '#8b5cf6',
    assistant: '#6366f1',
    error: '#ef4444',
    tool: '#f59e0b',
    thinking: '#6b7280',
};

function blockHeight(msg: Message): number {
    if (msg.role === 'user') return 4;
    if (msg.role === 'error') return 3;
    if (msg.role === 'assistant') {
        const len = msg.content?.length ?? 0;
        const toolCount = msg.toolCalls?.length ?? 0;
        return Math.min(20, Math.max(4, Math.round(len / 200) + toolCount * 3));
    }
    return 3;
}

function blockColor(msg: Message): string {
    if (msg.queued) return '#6b7280';
    return COLORS[msg.role] ?? COLORS.assistant;
}

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
    const [hoverIndex, setHoverIndex] = useState<number | null>(null);
    const dragStartRef = useRef({ y: 0, scrollTop: 0 });

    const blocks = useMemo<MinimapBlock[]>(() => {
        let y = 0;
        return messages.map((msg, i) => {
            const h = blockHeight(msg);
            const block: MinimapBlock = {
                index: i,
                color: blockColor(msg),
                height: h,
                y,
                preview: (msg.content ?? '').slice(0, 40),
            };
            y += h + 1;
            return block;
        });
    }, [messages]);

    const totalHeight = blocks.length > 0 ? blocks[blocks.length - 1].y + blocks[blocks.length - 1].height : 1;

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

    // Click to navigate
    const handleClick = useCallback(
        (e: React.MouseEvent) => {
            if (dragging) return;
            const rect = containerRef.current?.getBoundingClientRect();
            if (!rect) return;
            const clickRatio = (e.clientY - rect.top) / rect.height;
            const targetIndex = Math.round(clickRatio * (messages.length - 1));
            onScrollToIndex(Math.max(0, Math.min(messages.length - 1, targetIndex)));
        },
        [messages.length, onScrollToIndex, dragging],
    );

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

    if (messages.length < 5) return null;

    return (
        <div
            ref={containerRef}
            className="absolute right-0 top-0 bottom-0 w-[40px] border-l border-[var(--border)] bg-[var(--bg-secondary)]/80 z-5 cursor-pointer select-none"
            onClick={handleClick}
            title={hoverIndex !== null ? blocks[hoverIndex]?.preview : undefined}
        >
            {/* Blocks */}
            <div className="absolute inset-0 flex flex-col py-1 px-[6px] gap-[1px]"
                 style={{ transform: `scaleY(${Math.min(1, (containerRef.current?.clientHeight ?? 600) / totalHeight)})`, transformOrigin: 'top' }}>
                {blocks.map((b) => (
                    <div
                        key={b.index}
                        style={{
                            height: b.height,
                            backgroundColor: b.color,
                            borderRadius: 1,
                            opacity: hoverIndex === b.index ? 1 : 0.6,
                        }}
                        onMouseEnter={() => setHoverIndex(b.index)}
                        onMouseLeave={() => setHoverIndex(null)}
                    />
                ))}
            </div>

            {/* Viewport indicator */}
            <div
                className="absolute left-[2px] right-[2px] rounded-sm"
                style={{
                    top: `${viewportTop * 100}%`,
                    height: `${Math.max(viewportHeight * 100, 5)}%`,
                    background: 'var(--accent)',
                    opacity: dragging ? 0.25 : 0.12,
                    border: '1px solid',
                    borderColor: dragging ? 'var(--accent)' : 'transparent',
                    cursor: dragging ? 'grabbing' : 'grab',
                    transition: dragging ? 'none' : 'top 0.1s ease-out',
                }}
                onMouseDown={handleDragStart}
            />
        </div>
    );
}
