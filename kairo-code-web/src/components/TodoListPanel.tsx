import { useState } from 'react';
import { Check, Square, ChevronDown, ChevronRight, Loader2 } from 'lucide-react';
import type { Todo } from '@/types/agent';
import { savePref } from '@utils/userPrefs';

interface TodoListPanelProps {
    todos: Todo[];
    collapsed: boolean;
    onToggleCollapse: () => void;
}

const PREVIEW_COUNT = 5;

/** Priority dot color mapping */
function PriorityDot({ priority }: { priority?: Todo['priority'] }) {
    if (!priority) return null;
    const colors: Record<string, string> = {
        high: 'bg-rose-400',
        medium: 'bg-amber-400',
        low: 'bg-slate-400',
    };
    return <span className={`inline-block w-1.5 h-1.5 rounded-full shrink-0 mt-[5px] ${colors[priority]}`} />;
}

/** Group todos by status in display order: in_progress → pending → completed */
function groupByStatus(todos: Todo[]) {
    const inProgress = todos.filter((t) => t.status === 'in_progress');
    const pending = todos.filter((t) => t.status === 'pending');
    const completed = todos.filter((t) => t.status === 'completed');
    return { inProgress, pending, completed };
}

/**
 * Sticky panel that mirrors the agent's current {@code .kairo/todos.json} snapshot.
 * Sits above the message list inside the chat aside. Renders a progress bar plus
 * the first {@link PREVIEW_COUNT} items by default; expand to see the full list.
 *
 * Schema follows the backend {@code TodoWriteTool}: {@code {id, content, status, priority?}}.
 * {@code status === 'in_progress'} → animated pulse dot; {@code 'completed'} → strikethrough +
 * dimmed; {@code 'pending'} → empty square.
 */
export function TodoListPanel({ todos, collapsed, onToggleCollapse }: TodoListPanelProps) {
    const [showAll, setShowAll] = useState(false);

    if (todos.length === 0) return null;

    const completedCount = todos.filter((t) => t.status === 'completed').length;
    const total = todos.length;
    const progressPct = total > 0 ? (completedCount / total) * 100 : 0;
    const { inProgress, pending, completed } = groupByStatus(todos);

    // Flatten in display order and slice for preview
    const allGrouped = [...inProgress, ...pending, ...completed];
    const visible = showAll ? allGrouped : allGrouped.slice(0, PREVIEW_COUNT);
    const remaining = total - PREVIEW_COUNT;

    // Determine which groups are visible in preview mode
    const visibleInProgress = visible.filter((t) => t.status === 'in_progress');
    const visiblePending = visible.filter((t) => t.status === 'pending');
    const visibleCompleted = visible.filter((t) => t.status === 'completed');

    const handleToggle = () => {
        savePref('todoPanelCollapsed', !collapsed);
        onToggleCollapse();
    };

    return (
        <div className="border-b border-[var(--border)] bg-[var(--bg-secondary)] shrink-0">
            <button
                onClick={handleToggle}
                className="w-full flex items-center gap-2 px-3 py-2 hover:bg-[var(--bg-hover)] transition-colors"
                title={collapsed ? 'Expand todo list' : 'Collapse todo list'}
            >
                {collapsed
                    ? <ChevronRight size={12} className="text-[var(--text-muted)] shrink-0" />
                    : <ChevronDown size={12} className="text-[var(--text-muted)] shrink-0" />
                }
                <span className="text-xs font-semibold text-[var(--text-primary)] shrink-0">
                    Plan · {completedCount}/{total}
                </span>
                <div className="flex-1 h-1 rounded-full bg-[var(--bg-hover)] overflow-hidden ml-2">
                    <div
                        className="h-full bg-emerald-500 transition-all duration-500"
                        style={{ width: `${progressPct}%` }}
                    />
                </div>
            </button>

            {!collapsed && (
                <div className="px-3 pb-2 space-y-2">
                    {visibleInProgress.length > 0 && (
                        <StatusGroup label="IN PROGRESS" count={inProgress.length} items={visibleInProgress} color="text-amber-400" />
                    )}
                    {visiblePending.length > 0 && (
                        <StatusGroup label="TO DO" count={pending.length} items={visiblePending} color="text-[var(--text-muted)]" />
                    )}
                    {visibleCompleted.length > 0 && (
                        <StatusGroup label="DONE" count={completed.length} items={visibleCompleted} color="text-emerald-400" />
                    )}

                    {!showAll && remaining > 0 && (
                        <button
                            onClick={() => setShowAll(true)}
                            className="text-xs text-[var(--text-muted)] hover:text-[var(--accent)] transition-colors"
                        >
                            …{remaining} more
                        </button>
                    )}
                    {showAll && total > PREVIEW_COUNT && (
                        <button
                            onClick={() => setShowAll(false)}
                            className="text-xs text-[var(--text-muted)] hover:text-[var(--accent)] transition-colors"
                        >
                            Show less
                        </button>
                    )}
                </div>
            )}
        </div>
    );
}

function StatusGroup({ label, count, items, color }: { label: string; count: number; items: Todo[]; color: string }) {
    return (
        <div className="space-y-1">
            <div className={`text-[10px] font-semibold uppercase tracking-wider ${color} opacity-80`}>
                {label} ({count})
            </div>
            {items.map((todo) => (
                <TodoRow key={todo.id} todo={todo} />
            ))}
        </div>
    );
}

function TodoRow({ todo }: { todo: Todo }) {
    return (
        <div className={`flex items-start gap-2 ${todo.status === 'completed' ? 'opacity-50' : ''}`}>
            <StatusIcon status={todo.status} />
            <PriorityDot priority={todo.priority} />
            <span
                className={`text-xs leading-relaxed ${
                    todo.status === 'completed'
                        ? 'text-[var(--text-muted)] line-through'
                        : todo.status === 'in_progress'
                            ? 'text-[var(--text-primary)] font-medium'
                            : 'text-[var(--text-primary)]'
                }`}
            >
                {todo.content}
            </span>
        </div>
    );
}

function StatusIcon({ status }: { status: Todo['status'] }) {
    if (status === 'completed') {
        return <Check size={13} className="text-emerald-400 shrink-0 mt-0.5" />;
    }
    if (status === 'in_progress') {
        return <Loader2 size={13} className="text-amber-400 shrink-0 mt-0.5 animate-spin" />;
    }
    return <Square size={13} className="text-[var(--text-muted)] shrink-0 mt-0.5" />;
}
