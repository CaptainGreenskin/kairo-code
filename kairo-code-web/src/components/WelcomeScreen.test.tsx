import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { WelcomeScreen } from './WelcomeScreen';

describe('WelcomeScreen', () => {
    it('renders brand name', () => {
        render(<WelcomeScreen onSelectPrompt={() => {}} />);
        expect(screen.getByText('Kairo Code')).toBeInTheDocument();
    });

    it('renders quick prompt buttons', () => {
        render(<WelcomeScreen onSelectPrompt={() => {}} />);
        expect(screen.getByText('Explore project structure')).toBeInTheDocument();
        expect(screen.getByText('Run tests')).toBeInTheDocument();
    });

    it('calls onSelectPrompt with correct prompt when clicked', () => {
        const onSelect = vi.fn();
        render(<WelcomeScreen onSelectPrompt={onSelect} />);
        fireEvent.click(screen.getByText('Run tests'));
        expect(onSelect).toHaveBeenCalledWith(
            expect.stringContaining('test suite'),
        );
    });

    it('renders app version when provided', () => {
        render(<WelcomeScreen onSelectPrompt={() => {}} appVersion="1.2.3" />);
        expect(screen.getByText('v1.2.3')).toBeInTheDocument();
    });

    it('does not render version when not provided', () => {
        render(<WelcomeScreen onSelectPrompt={() => {}} />);
        expect(screen.queryByText(/^v\d/)).not.toBeInTheDocument();
    });

    it('renders all 6 quick prompt cards', () => {
        render(<WelcomeScreen onSelectPrompt={() => {}} />);
        const buttons = screen.getAllByRole('button');
        expect(buttons.length).toBe(6);
    });

    it('renders tagline text', () => {
        render(<WelcomeScreen onSelectPrompt={() => {}} />);
        expect(screen.getByText(/AI coding agent in your browser/i)).toBeInTheDocument();
    });

    it('renders keyboard shortcut hint', () => {
        render(<WelcomeScreen onSelectPrompt={() => {}} />);
        expect(screen.getByText(/for commands/i)).toBeInTheDocument();
    });

    it('does not render recent sessions when not provided', () => {
        render(<WelcomeScreen onSelectPrompt={() => {}} />);
        expect(screen.queryByText('Recent sessions')).not.toBeInTheDocument();
    });

    it('does not render recent sessions when empty array', () => {
        render(<WelcomeScreen onSelectPrompt={() => {}} recentSessions={[]} />);
        expect(screen.queryByText('Recent sessions')).not.toBeInTheDocument();
    });

    it('renders recent sessions when provided', () => {
        const sessions = [
            { id: 's1', name: 'Fix auth bug', updatedAt: Date.now() - 3600_000 },
            { id: 's2', name: 'Add search feature', updatedAt: Date.now() - 86400_000 },
        ];
        render(<WelcomeScreen onSelectPrompt={() => {}} recentSessions={sessions} />);
        expect(screen.getByText('Recent sessions')).toBeInTheDocument();
        expect(screen.getByText('Fix auth bug')).toBeInTheDocument();
        expect(screen.getByText('Add search feature')).toBeInTheDocument();
    });

    it('renders relative time for recent sessions', () => {
        const sessions = [
            { id: 's1', name: 'Test session', updatedAt: Date.now() - 3600_000 },
        ];
        render(<WelcomeScreen onSelectPrompt={() => {}} recentSessions={sessions} />);
        expect(screen.getByText(/hour.*ago/i)).toBeInTheDocument();
    });

    it('renders last message preview when provided', () => {
        const sessions = [
            { id: 's1', name: 'Session A', lastMessage: 'Can you help me refactor the database layer?' },
        ];
        render(<WelcomeScreen onSelectPrompt={() => {}} recentSessions={sessions} />);
        expect(screen.getByText('Can you help me refactor the database layer?')).toBeInTheDocument();
    });

    it('truncates long last messages', () => {
        const longMsg = 'a'.repeat(100);
        const sessions = [
            { id: 's1', name: 'Session A', lastMessage: longMsg },
        ];
        render(<WelcomeScreen onSelectPrompt={() => {}} recentSessions={sessions} />);
        expect(screen.queryByText(longMsg)).not.toBeInTheDocument();
    });

    it('calls onSelectSession when a recent session card is clicked', () => {
        const onSelect = vi.fn();
        const sessions = [{ id: 's1', name: 'Click me' }];
        render(
            <WelcomeScreen onSelectPrompt={() => {}} recentSessions={sessions} onSelectSession={onSelect} />,
        );
        fireEvent.click(screen.getByText('Click me'));
        expect(onSelect).toHaveBeenCalledWith('s1');
    });

    it('limits recent sessions to 5', () => {
        const sessions = Array.from({ length: 8 }, (_, i) => ({
            id: `s${i}`,
            name: `Session ${i}`,
        }));
        render(<WelcomeScreen onSelectPrompt={() => {}} recentSessions={sessions} />);
        const buttons = screen.getAllByRole('button');
        // 5 session cards + 6 quick prompt buttons = 11
        expect(buttons.length).toBe(11);
    });
});
