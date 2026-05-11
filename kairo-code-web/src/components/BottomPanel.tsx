import { lazy, Suspense } from 'react';
import { useLayoutStore } from '@store/layoutStore';

const ShellPanel = lazy(() =>
    import('@components/ShellPanel').then((m) => ({ default: m.ShellPanel })),
);

interface BottomPanelProps {
    workspaceId?: string;
    externalCommand?: string;
}

export function BottomPanel({ workspaceId, externalCommand }: BottomPanelProps) {
    const open = useLayoutStore((s) => s.bottomPanelOpen);
    const toggle = useLayoutStore((s) => s.toggleBottomPanel);

    if (!open) return null;

    return (
        <div className="flex flex-col h-full bg-[#0d0d0d] border-t border-[var(--border)] overflow-hidden">
            <Suspense
                fallback={
                    <div className="flex-1 flex items-center justify-center text-xs text-[var(--text-muted)]">
                        Loading shell…
                    </div>
                }
            >
                <ShellPanel
                    onClose={toggle}
                    embedded
                    workspaceId={workspaceId}
                    externalCommand={externalCommand}
                />
            </Suspense>
        </div>
    );
}
