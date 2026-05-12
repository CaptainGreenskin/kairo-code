import { useState } from 'react';
import { Check, Square, ClipboardList, Loader2 } from 'lucide-react';
import type { Todo } from '@/types/agent';

interface InlineTodoCardProps {
    todos: Todo[];
    overview?: string;
}

const PREVIEW_COUNT = 6;

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
 * Inline rendering of a {@code todo_write} tool snapshot inside the message stream.
 *
 * <p>Each {@code todo_write} call produces an immutable card capturing the agent's plan at
 * that moment, so the chat history shows how the plan evolved over time. Distinct from the
 * sticky {@link TodoListPanel}, which always reflects the *latest* state.
 */
export function InlineTodoCard({ todos, overview }: InlineTodoCardProps) {
    const [showAll, setShowAll] = useState(false);

    if (!todos || todos.length === 0) {
        return (
            <div className="my-2 border border-[var(--border)] rounded-lg overflow-hidden bg-[var(--bg-secondary)] px-3 py-2 text-xs text-[var(--text-muted)] italic">
                Empty todo list — todo_write was called with no items
            </div>
        );
    }

    const completedCount = todos.filter((t) => t.status === 'completed').length;
    const total = todos.length;
    const progressPct = total > 0 ? (completedCount / total) * 100 : 0;
    const { inProgress, pending, completed } = groupByStatus(todos);

    // For preview mode, flatten groups and slice
    const allGrouped = [...inProgress, ...pending, ...completed];
    const visible = showAll ? allGrouped : allGrouped.slice(0, PREVIEW_COUNT);
    const remaining = total - PREVIEW_COUNT;

    // Determine which groups are visible in preview mode
    const visibleInProgress = visible.filter((t) => t.status === 'in_progress');
    const visiblePending = visible.filter((t) => t.status === 'pending');
    const visibleCompleted = visible.filter((t) => t.status === 'completed');

    return (
        <div className="my-2 border border-amber-500/20 rounded-lg overflow-hidden bg-gradient-to-br from-amber-500/5 to-transparent">
            {/* Header */}
            <div className="px-3 py-2 flex items-center gap-2 bg-amber-500/10 border-b border-amber-500/20">
                <ClipboardList size={14} className="text-amber-300 shrink-0" />
                <span className="text-sm font-semibold text-amber-200">
                    Plan · {total} To-do{total === 1 ? '' : 's'}
                </span>
                <span className="text-xs text-[var(--text-muted)] ml-auto">
                    {completedCount}/{total}
                </span>
            </div>

            {/* Progress bar */}
            <div className="px-3 pt-2">
                <div className="h-1 rounded-full bg-[var(--bg-hover)] overflow-hidden">
                    <div
                        className="h-full bg-emerald-500 transition-all duration-500"
                        style={{ width: `${progressPct}%` }}
                    />
                </div>
            </div>

            {overview && (
                <div className="px-3 py-2 text-xs text-[var(--text-secondary)] italic border-b border-[var(--border)]">
                    {overview}
                </div>
            )}

            {/* Grouped task list */}
            <div className="px-3 py-2 space-y-2">
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
                        className="text-xs text-[var(--text-muted)] hover:text-[var(--accent)] transition-colors mt-1"
                    >
                        …{remaining} more
                    </button>
                )}
                {showAll && total > PREVIEW_COUNT && (
                    <button
                        onClick={() => setShowAll(false)}
                        className="text-xs text-[var(--text-muted)] hover:text-[var(--accent)] transition-colors mt-1"
                    >
                        Show less
                    </button>
                )}
            </div>
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
                <TodoItem key={todo.id} todo={todo} />
            ))}
        </div>
    );
}

function TodoItem({ todo }: { todo: Todo }) {
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

/**
 * Best-effort parser for the {@code todo_write} tool input shape. Backend schema:
 * {@code {todos: [{id, content, status, priority?}]}}. Returns an empty array when the
 * input doesn't match — caller renders a friendly empty state.
 */
export function parseTodoWriteInput(input: Record<string, unknown> | undefined): {
    todos: Todo[];
    overview?: string;
} {
    if (!input || typeof input !== 'object') return { todos: [] };
    const overview = typeof input.overview === 'string' ? input.overview : undefined;
    // Backend TodoWriteTool accepts both: {todos: [...]} (array) or {todos: "[...]"} (stringified).
    // Models routinely pick the stringified shape because the @ToolParam type is String. Mirror
    // that coercion here so the inline card doesn't render "empty" when the call actually wrote N items.
    let raw: unknown = input.todos;
    if (typeof raw === 'string' && raw.trim().startsWith('[')) {
        try {
            raw = JSON.parse(raw);
        } catch {
            return { todos: [], overview };
        }
    }
    if (!Array.isArray(raw)) return { todos: [], overview };
    const todos: Todo[] = [];
    for (const item of raw) {
        if (!item || typeof item !== 'object') continue;
        const obj = item as Record<string, unknown>;
        const id = typeof obj.id === 'string' ? obj.id : String(todos.length);
        const content = typeof obj.content === 'string'
            ? obj.content
            : typeof obj.subject === 'string' ? obj.subject : '';
        const statusRaw = typeof obj.status === 'string' ? obj.status : 'pending';
        const status: Todo['status'] = statusRaw === 'in_progress' || statusRaw === 'completed'
            ? statusRaw
            : 'pending';
        const priorityRaw = typeof obj.priority === 'string' ? obj.priority : undefined;
        const priority = priorityRaw === 'high' || priorityRaw === 'medium' || priorityRaw === 'low'
            ? (priorityRaw as Todo['priority'])
            : undefined;
        todos.push({ id, content, status, priority });
    }
    return { todos, overview };
}
