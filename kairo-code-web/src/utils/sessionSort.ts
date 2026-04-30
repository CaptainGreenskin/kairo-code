export type SessionSortOrder = 'date-desc' | 'date-asc' | 'name-asc' | 'name-desc';

export interface SortableSession {
    id: string;
    name?: string;
    createdAt?: number;
}

/**
 * Sorts sessions by the given order. Returns a new array (does not mutate).
 */
export function sortSessions<T extends SortableSession>(
    sessions: T[],
    order: SessionSortOrder,
): T[] {
    return [...sessions].sort((a, b) => {
        switch (order) {
            case 'date-desc':
                return (b.createdAt ?? 0) - (a.createdAt ?? 0);
            case 'date-asc':
                return (a.createdAt ?? 0) - (b.createdAt ?? 0);
            case 'name-asc':
                return (a.name ?? a.id).localeCompare(b.name ?? b.id);
            case 'name-desc':
                return (b.name ?? b.id).localeCompare(a.name ?? a.id);
        }
    });
}
