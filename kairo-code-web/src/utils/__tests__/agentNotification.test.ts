import { describe, it, expect, beforeEach } from 'vitest';
import { setTitleBadge, notifyAgent } from '../agentNotification';

describe('agentNotification', () => {
    beforeEach(() => {
        document.title = 'kairo-code';
    });

    describe('setTitleBadge', () => {
        it('adds badge count to title', () => {
            setTitleBadge(1);
            expect(document.title).toBe('(1) kairo-code');
        });

        it('updates badge count', () => {
            setTitleBadge(1);
            setTitleBadge(3);
            expect(document.title).toBe('(3) kairo-code');
        });

        it('restores original title when count is 0', () => {
            setTitleBadge(2);
            setTitleBadge(0);
            expect(document.title).toBe('kairo-code');
        });

        it('handles negative count as 0', () => {
            setTitleBadge(-1);
            expect(document.title).toBe('kairo-code');
        });
    });

    describe('notifyAgent', () => {
        it('does not throw when Notification is not available', () => {
            expect(() => notifyAgent('done')).not.toThrow();
        });
    });
});
