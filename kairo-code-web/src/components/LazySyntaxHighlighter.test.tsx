import { describe, it, expect } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { LazySyntaxHighlighter } from './LazySyntaxHighlighter';

describe('LazySyntaxHighlighter', () => {
    it('shows plaintext fallback before the syntax highlighter chunk resolves', () => {
        render(<LazySyntaxHighlighter language="js">{'const x = 1;'}</LazySyntaxHighlighter>);
        const fallback = screen.getByText('const x = 1;');
        // Fallback wraps the raw text in <pre><code>...</code></pre>
        expect(fallback.tagName.toLowerCase()).toBe('code');
        expect(fallback.parentElement?.tagName.toLowerCase()).toBe('pre');
    });

    it('renders highlighted code once the chunk resolves', async () => {
        const { container } = render(
            <LazySyntaxHighlighter language="js">{'const x = 1;'}</LazySyntaxHighlighter>,
        );
        // Once resolved, react-syntax-highlighter wraps tokens in <span> with style
        await waitFor(() => {
            const tokens = container.querySelectorAll('span');
            expect(tokens.length).toBeGreaterThan(0);
        });
        // The original text should still be present in the rendered output
        expect(container.textContent).toContain('const x = 1;');
    });
});
