import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MessageReaction } from './MessageReaction';

beforeEach(() => {
    localStorage.clear();
});

describe('MessageReaction', () => {
    it('renders thumbs up and down buttons', () => {
        render(<MessageReaction messageId="msg-1" />);
        expect(screen.getByTitle('Good response')).toBeDefined();
        expect(screen.getByTitle('Bad response')).toBeDefined();
    });

    it('clicking thumbs up stores reaction in localStorage', () => {
        render(<MessageReaction messageId="msg-2" />);
        fireEvent.click(screen.getByTitle('Good response'));
        const stored = JSON.parse(localStorage.getItem('kairo-message-reactions') ?? '{}');
        expect(stored['msg-2']?.reaction).toBe('up');
    });

    it('clicking thumbs down stores reaction in localStorage', () => {
        render(<MessageReaction messageId="msg-3" />);
        fireEvent.click(screen.getByTitle('Bad response'));
        const stored = JSON.parse(localStorage.getItem('kairo-message-reactions') ?? '{}');
        expect(stored['msg-3']?.reaction).toBe('down');
    });

    it('clicking same button twice toggles reaction off', () => {
        render(<MessageReaction messageId="msg-4" />);
        fireEvent.click(screen.getByTitle('Good response'));
        fireEvent.click(screen.getByTitle('Good response'));
        const stored = JSON.parse(localStorage.getItem('kairo-message-reactions') ?? '{}');
        expect(stored['msg-4']).toBeUndefined();
    });

    it('switching from up to down updates stored reaction', () => {
        render(<MessageReaction messageId="msg-5" />);
        fireEvent.click(screen.getByTitle('Good response'));
        fireEvent.click(screen.getByTitle('Bad response'));
        const stored = JSON.parse(localStorage.getItem('kairo-message-reactions') ?? '{}');
        expect(stored['msg-5']?.reaction).toBe('down');
    });
});
