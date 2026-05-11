import { useRef, useCallback } from 'react';

interface ResizeHandleProps {
    /** Current size of the resized panel, in px (width for vertical, height for horizontal). */
    width: number;
    /** Called continuously during drag with the new size. */
    onResize: (newSize: number) => void;
    /** Called once when drag ends, intended for persistence. */
    onResizeEnd?: (finalSize: number) => void;
    /**
     * For vertical orientation: 'left' panel grows when dragged right, 'right' panel shrinks.
     * For horizontal orientation: 'top' panel grows when dragged down, 'bottom' panel shrinks
     * when dragged down (used for a dock-from-bottom panel that should grow when handle moves up).
     */
    side: 'left' | 'right' | 'top' | 'bottom';
    minWidth?: number;
    maxWidth?: number;
    /** Default 'vertical' (col-resize). 'horizontal' uses row-resize and tracks dy. */
    orientation?: 'vertical' | 'horizontal';
}

export function ResizeHandle({
    width,
    onResize,
    onResizeEnd,
    side,
    minWidth = 200,
    maxWidth = 600,
    orientation = 'vertical',
}: ResizeHandleProps) {
    const start = useRef(0);
    const startSize = useRef(0);
    const latest = useRef(width);

    const onPointerDown = useCallback(
        (e: React.PointerEvent<HTMLDivElement>) => {
            e.preventDefault();
            start.current = orientation === 'vertical' ? e.clientX : e.clientY;
            startSize.current = width;
            latest.current = width;
            (e.target as HTMLDivElement).setPointerCapture(e.pointerId);
            document.body.style.cursor = orientation === 'vertical' ? 'col-resize' : 'row-resize';
            document.body.style.userSelect = 'none';
        },
        [width, orientation],
    );

    const onPointerMove = useCallback(
        (e: React.PointerEvent<HTMLDivElement>) => {
            if (!(e.target as HTMLDivElement).hasPointerCapture(e.pointerId)) return;
            const cursor = orientation === 'vertical' ? e.clientX : e.clientY;
            const delta = cursor - start.current;
            // 'left'/'top' grow with positive delta; 'right'/'bottom' shrink with positive delta
            const grows = side === 'left' || side === 'top';
            const next = grows ? startSize.current + delta : startSize.current - delta;
            const clamped = Math.max(minWidth, Math.min(maxWidth, next));
            latest.current = clamped;
            onResize(clamped);
        },
        [onResize, side, minWidth, maxWidth, orientation],
    );

    const onPointerUp = useCallback(
        (e: React.PointerEvent<HTMLDivElement>) => {
            (e.target as HTMLDivElement).releasePointerCapture(e.pointerId);
            document.body.style.cursor = '';
            document.body.style.userSelect = '';
            onResizeEnd?.(latest.current);
        },
        [onResizeEnd],
    );

    const isVertical = orientation === 'vertical';

    return (
        <div
            onPointerDown={onPointerDown}
            onPointerMove={onPointerMove}
            onPointerUp={onPointerUp}
            onPointerCancel={onPointerUp}
            role="separator"
            aria-orientation={isVertical ? 'vertical' : 'horizontal'}
            aria-valuenow={width}
            aria-valuemin={minWidth}
            aria-valuemax={maxWidth}
            className={
                isVertical
                    ? 'group relative w-1 shrink-0 cursor-col-resize hover:bg-[var(--accent)]/40 active:bg-[var(--accent)]/60 transition-colors'
                    : 'group relative h-1 shrink-0 cursor-row-resize hover:bg-[var(--accent)]/40 active:bg-[var(--accent)]/60 transition-colors'
            }
            title="Drag to resize"
        >
            <div
                className={
                    isVertical
                        ? 'absolute inset-y-0 -left-1 -right-1'
                        : 'absolute inset-x-0 -top-1 -bottom-1'
                }
            />
        </div>
    );
}
