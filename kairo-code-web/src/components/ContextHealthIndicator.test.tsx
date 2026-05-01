import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { ContextHealthIndicator } from './ContextHealthIndicator';

describe('ContextHealthIndicator', () => {
    beforeEach(() => {
        vi.useFakeTimers();
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    it('renders the percentage of the context window in use', () => {
        render(<ContextHealthIndicator tokenUsage={50_000} maxTokens={100_000} isCompacting={false} />);
        expect(screen.getByText('50%')).toBeInTheDocument();
        const root = screen.getByTestId('context-health-indicator');
        expect(root).toHaveAttribute('title', expect.stringContaining('50,000'));
        expect(root).toHaveAttribute('title', expect.stringContaining('100,000'));
    });

    it('hides itself when maxTokens is zero', () => {
        const { container } = render(
            <ContextHealthIndicator tokenUsage={1_000} maxTokens={0} isCompacting={false} />,
        );
        expect(container.firstChild).toBeNull();
    });

    it('clamps the bar at 100% when usage exceeds the window', () => {
        render(<ContextHealthIndicator tokenUsage={300_000} maxTokens={100_000} isCompacting={false} />);
        expect(screen.getByText('100%')).toBeInTheDocument();
    });

    it('shows the compacted flash when isCompacting flips to true and clears it after 3s', () => {
        const { rerender } = render(
            <ContextHealthIndicator tokenUsage={80_000} maxTokens={100_000} isCompacting={false} />,
        );
        expect(screen.queryByTestId('context-compacted-flash')).not.toBeInTheDocument();

        rerender(<ContextHealthIndicator tokenUsage={80_000} maxTokens={100_000} isCompacting={true} />);
        expect(screen.getByTestId('context-compacted-flash')).toBeInTheDocument();

        // Even after the parent flips back to false, the flash persists until the 3s timer fires.
        rerender(<ContextHealthIndicator tokenUsage={80_000} maxTokens={100_000} isCompacting={false} />);
        expect(screen.getByTestId('context-compacted-flash')).toBeInTheDocument();

        act(() => {
            vi.advanceTimersByTime(3000);
        });
        expect(screen.queryByTestId('context-compacted-flash')).not.toBeInTheDocument();
    });

    it('uses different color tiers based on usage ratio', () => {
        const { rerender } = render(
            <ContextHealthIndicator tokenUsage={10_000} maxTokens={100_000} isCompacting={false} />,
        );
        expect(screen.getByText('10%').className).toContain('emerald');

        rerender(<ContextHealthIndicator tokenUsage={70_000} maxTokens={100_000} isCompacting={false} />);
        expect(screen.getByText('70%').className).toContain('yellow');

        rerender(<ContextHealthIndicator tokenUsage={90_000} maxTokens={100_000} isCompacting={false} />);
        expect(screen.getByText('90%').className).toContain('red');
    });
});
