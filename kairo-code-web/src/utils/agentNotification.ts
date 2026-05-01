export type NotificationType = 'done' | 'approval' | 'error';

const NOTIFICATION_TITLES: Record<NotificationType, string> = {
    done: 'Agent done',
    approval: 'Approval needed',
    error: 'Agent error',
};

const NOTIFICATION_BODIES: Record<NotificationType, string> = {
    done: 'Your agent task has completed.',
    approval: 'A tool is waiting for your approval.',
    error: 'Agent encountered an error.',
};

/**
 * Requests browser notification permission if not already granted.
 * Returns true if permission is granted (or was already granted).
 */
export async function requestNotificationPermission(): Promise<boolean> {
    if (!('Notification' in window)) return false;
    if (Notification.permission === 'granted') return true;
    if (Notification.permission === 'denied') return false;
    const result = await Notification.requestPermission();
    return result === 'granted';
}

/**
 * Sends a browser notification if the document is hidden (user in another tab).
 * No-ops when the tab is visible.
 */
export function notifyAgent(type: NotificationType): void {
    if (!document.hidden) return;
    if (!('Notification' in window) || Notification.permission !== 'granted') return;

    const n = new Notification(NOTIFICATION_TITLES[type], {
        body: NOTIFICATION_BODIES[type],
        tag: 'kairo-agent',
    });

    setTimeout(() => n.close(), 6000);
    n.onclick = () => { window.focus(); n.close(); };
}

/**
 * Updates the page title with a badge count.
 * count=0 restores original title.
 */
let _originalTitle: string | undefined;

function getOriginalTitle(): string {
    if (_originalTitle === undefined) {
        _originalTitle = document.title;
    }
    return _originalTitle;
}

export function setTitleBadge(count: number): void {
    if (count <= 0) {
        document.title = getOriginalTitle();
    } else {
        const base = getOriginalTitle().replace(/^\(\d+\)\s*/, '');
        document.title = `(${count}) ${base}`;
    }
}
