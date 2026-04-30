/**
 * Format a timestamp as a relative time string.
 * e.g., "just now", "2m ago", "1h ago", "Yesterday 14:30", "May 1, 10:30"
 */
export function formatRelativeTime(timestamp: number): string {
    const now = Date.now();
    const diff = now - timestamp;
    const seconds = Math.floor(diff / 1000);
    const minutes = Math.floor(diff / 60000);
    const hours = Math.floor(diff / 3600000);
    const days = Math.floor(diff / 86400000);

    if (seconds < 10) return 'just now';
    if (seconds < 60) return `${seconds}s ago`;
    if (minutes < 60) return `${minutes}m ago`;
    if (hours < 24) return `${hours}h ago`;
    if (days === 1) return `Yesterday ${formatHHMM(timestamp)}`;
    if (days < 7) return `${days}d ago`;
    return formatDate(timestamp);
}

function formatHHMM(timestamp: number): string {
    return new Date(timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function formatDate(timestamp: number): string {
    return new Date(timestamp).toLocaleDateString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/**
 * Format a timestamp as a full date-time string for tooltip display.
 */
export function formatAbsoluteTime(timestamp: number): string {
    return new Date(timestamp).toLocaleString([], {
        year: 'numeric', month: 'short', day: 'numeric',
        hour: '2-digit', minute: '2-digit', second: '2-digit',
    });
}
