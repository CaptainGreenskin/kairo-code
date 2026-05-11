import { useState } from 'react';
import { Check, Circle, ChevronDown, ChevronRight, Loader2 } from 'lucide-react';
import type { Todo } from '@/types/agent';
import { savePref } from '@utils/userPrefs';

interface TodoListPanelProps {
    todos: Todo[];
    collapsed: boolean;
    onToggleCollapse: () => void;
}

const PREVIEW_COUNT = 5;

/**
 * Sticky panel that mirrors the agent's current {@code .kairo/todos.json} snapshot.
 * Sits above the message list inside the chat aside. Renders a progress bar plus
 * the first {@link PREVIEW_COUNT} items by default; expand to see the full list.
 *
 * Schema follows the backend {@code TodoWriteTool}: {@code {id, content, status, priority?}}.
 * {@code status === 'in_progress'} → animated pulse dot; {@code 'completed'} → strikethrough +
 * dimmed; {@code 'pending'} → empty circle.
 */
export function TodoListPanel({ todos, collapsed, onToggleCollapse }: TodoListPanelProps) {
    const [showAll, setShowAll] = useState(false);

    if (todos.length === 0) return null;

    const completedCount = todos.filter((t) => t.status === 'completed').length;
    const total = todos.length;
    const progressPct = total > 0 ? (completedCount / total) * 100 : 0;
    const visibleTodos = showAll ? todos : todos.slice(0, PREVIEW_COUNT);
    const remaining = total - PREVIEW_COUNT;

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
                    Plan · {completedCount} of {total} Done
                </span>
                <div className="flex-1 h-1 rounded-full bg-[var(--bg-hover)] overflow-hidden ml-2">
                    <div
                        className="h-full bg-[var(--accent)] transition-all duration-500"
                        style={{ width: `${progressPct}%` }}
                    />
                </div>
            </button>

            {!collapsed && (
                <div className="px-3 pb-2 space-y-1.5">
                    {visibleTodos.map((todo) => (
                        <TodoRow key={todo.id} todo={todo} />
                    ))}
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

function TodoRow({ todo }: { todo: Todo }) {
    const text = todo.content;
    return (
        <div className="flex items-start gap-2">
            <StatusIcon status={todo.status} />
            <span
                className={`text-xs leading-relaxed ${
                    todo.status === 'completed'
                        ? 'text-[var(--text-muted)] line-through opacity-60'
                        : todo.status === 'in_progress'
                            ? 'text-[var(--text-primary)] font-medium'
                            : 'text-[var(--text-primary)]'
                }`}
            >
                {text}
            </span>
        </div>
    );
}

function StatusIcon({ status }: { status: Todo['status'] }) {
    if (status === 'completed') {
        return <Check size={13} className="text-emerald-400 shrink-0 mt-0.5" />;
    }
    if (status === 'in_progress') {
        return <Loader2 size={13} className="text-[var(--accent)] shrink-0 mt-0.5 animate-spin" />;
    }
    return <Circle size={13} className="text-[var(--text-muted)] shrink-0 mt-0.5" />;
}
