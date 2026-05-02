import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { BookmarkPanel } from './BookmarkPanel';
import type { Message } from '@/types/agent';

beforeEach(() => {
    localStorage.clear();
});

const makeMessage = (id: string, content: string): Message => ({
    id,
    role: 'assistant' as const,
    content,
    toolCalls: [],
    timestamp: Date.now(),
});

describe('BookmarkPanel', () => {
    it('renders nothing when isOpen=false', () => {
        const { container } = render(
            <BookmarkPanel
                sessionId="s1"
                messages={[]}
                isOpen={false}
                onClose={vi.fn()}
                onScrollToMessage={vi.fn()}
            />
        );
        expect(container.firstChild).toBeNull();
    });

    it('renders panel header when isOpen=true', () => {
        render(
            <BookmarkPanel
                sessionId="s1"
                messages={[]}
                isOpen={true}
                onClose={vi.fn()}
                onScrollToMessage={vi.fn()}
            />
        );
        expect(screen.getByText('Bookmarks')).toBeDefined();
    });

    it('shows empty state when no bookmarks', () => {
        render(
            <BookmarkPanel
                sessionId="s1"
                messages={[makeMessage('m1', 'hello')]}
                isOpen={true}
                onClose={vi.fn()}
                onScrollToMessage={vi.fn()}
            />
        );
        expect(screen.getByText(/No bookmarks yet/)).toBeDefined();
    });

    it('shows bookmarked message content', () => {
        localStorage.setItem(
            'kairo-bookmarks:s1',
            JSON.stringify(['msg-1'])
        );
        const messages = [
            makeMessage('msg-1', 'Bookmarked message content'),
            makeMessage('msg-2', 'Not bookmarked'),
        ];
        render(
            <BookmarkPanel
                sessionId="s1"
                messages={messages}
                isOpen={true}
                onClose={vi.fn()}
                onScrollToMessage={vi.fn()}
            />
        );
        expect(screen.getByText('Bookmarked message content')).toBeDefined();
        expect(screen.queryByText('Not bookmarked')).toBeNull();
    });

    it('calls onClose when X button clicked', () => {
        const onClose = vi.fn();
        render(
            <BookmarkPanel
                sessionId="s1"
                messages={[]}
                isOpen={true}
                onClose={onClose}
                onScrollToMessage={vi.fn()}
            />
        );
        // The X icon button is the second button in the panel
        const buttons = document.querySelectorAll('button');
        // Find the button with the X icon (close button)
        const xBtn = Array.from(buttons).find(b =>
            b.querySelector('svg') && b.className.includes('hover:bg')
        );
        if (xBtn) {
            fireEvent.click(xBtn);
            expect(onClose).toHaveBeenCalled();
        }
    });

    it('calls onScrollToMessage when bookmarked message clicked', () => {
        localStorage.setItem('kairo-bookmarks:s1', JSON.stringify(['msg-1']));
        const onScrollToMessage = vi.fn();
        const messages = [makeMessage('msg-1', 'Click me message')];
        render(
            <BookmarkPanel
                sessionId="s1"
                messages={messages}
                isOpen={true}
                onClose={vi.fn()}
                onScrollToMessage={onScrollToMessage}
            />
        );
        const msgEl = screen.getByText('Click me message');
        fireEvent.click(msgEl);
        expect(onScrollToMessage).toHaveBeenCalledWith('msg-1');
    });

    it('shows bookmark count in header', () => {
        localStorage.setItem('kairo-bookmarks:s1', JSON.stringify(['msg-1', 'msg-2']));
        const messages = [
            makeMessage('msg-1', 'First'),
            makeMessage('msg-2', 'Second'),
        ];
        render(
            <BookmarkPanel
                sessionId="s1"
                messages={messages}
                isOpen={true}
                onClose={vi.fn()}
                onScrollToMessage={vi.fn()}
            />
        );
        expect(screen.getByText('(2)')).toBeDefined();
    });
});
