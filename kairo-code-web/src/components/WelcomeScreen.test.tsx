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
});
