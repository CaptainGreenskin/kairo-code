import { describe, it, expect } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { LazyMarkdown } from './LazyMarkdown';

describe('LazyMarkdown', () => {
    it('shows plaintext fallback before the markdown chunk resolves', () => {
        render(<LazyMarkdown># hello world</LazyMarkdown>);
        // Suspense fallback renders the raw children inside a <pre>
        const fallback = screen.getByText('# hello world');
        expect(fallback.tagName.toLowerCase()).toBe('pre');
    });

    it('renders parsed markdown after the chunk resolves', async () => {
        render(<LazyMarkdown># hello world</LazyMarkdown>);
        const heading = await waitFor(() => screen.getByRole('heading', { level: 1 }));
        expect(heading).toHaveTextContent('hello world');
    });

    it('forwards components prop to react-markdown', async () => {
        render(
            <LazyMarkdown
                components={{
                    h1: ({ children }) => <h1 data-testid="custom-h1">{children}</h1>,
                }}
            >
                # custom
            </LazyMarkdown>,
        );
        const customHeading = await waitFor(() => screen.getByTestId('custom-h1'));
        expect(customHeading).toHaveTextContent('custom');
    });
});
