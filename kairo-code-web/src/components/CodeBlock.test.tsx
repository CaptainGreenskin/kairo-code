import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { CodeBlock } from './CodeBlock';

vi.mock('./LazySyntaxHighlighter', () => ({
    LazySyntaxHighlighter: ({ children }: { children: string }) => <pre data-testid="highlighter">{children}</pre>,
}));

const SHORT_CODE = 'const x = 1;\nconst y = 2;';
const LONG_CODE = Array.from({ length: 50 }, (_, i) => `const line${i} = ${i};`).join('\n');

describe('CodeBlock', () => {
    it('renders language label in header', () => {
        render(<CodeBlock language="typescript" content={SHORT_CODE} />);
        expect(screen.getByText('typescript')).toBeDefined();
    });

    it('shows copy button', () => {
        render(<CodeBlock language="js" content={SHORT_CODE} />);
        const copyBtn = screen.getByTitle('Copy');
        expect(copyBtn).toBeDefined();
    });

    it('shows line numbers toggle button', () => {
        render(<CodeBlock language="js" content={SHORT_CODE} />);
        const hashBtn = screen.getByTitle('Toggle line numbers');
        expect(hashBtn).toBeDefined();
    });

    it('shows fullscreen button', () => {
        render(<CodeBlock language="js" content={SHORT_CODE} />);
        expect(screen.getByTitle('Fullscreen')).toBeDefined();
    });

    it('shows expand button for long code (>40 lines)', () => {
        render(<CodeBlock language="js" content={LONG_CODE} />);
        const showMoreBtn = screen.getByText(/Show \d+ more lines/);
        expect(showMoreBtn).toBeDefined();
    });

    it('does NOT show expand button for short code', () => {
        render(<CodeBlock language="js" content={SHORT_CODE} />);
        expect(screen.queryByText(/more lines/)).toBeNull();
    });

    it('clicking expand shows full code', () => {
        render(<CodeBlock language="js" content={LONG_CODE} />);
        const btn = screen.getByText(/Show \d+ more lines/);
        fireEvent.click(btn);
        expect(screen.getByText('Show less')).toBeDefined();
    });

    it('fullscreen modal opens on fullscreen click', () => {
        render(<CodeBlock language="py" content={SHORT_CODE} />);
        fireEvent.click(screen.getByTitle('Fullscreen'));
        expect(screen.getByText(/Close/)).toBeDefined();
    });

    it('renders text as language fallback when language is empty', () => {
        render(<CodeBlock language="" content={SHORT_CODE} />);
        expect(screen.getByText('text')).toBeDefined();
    });

    it('shows Run button for shell language', () => {
        render(<CodeBlock language="bash" content="echo hello" onRun={() => {}} />);
        expect(screen.getByTitle('Run command')).toBeDefined();
    });

    it('shows Run button for zsh language', () => {
        render(<CodeBlock language="zsh" content="echo hello" onRun={() => {}} />);
        expect(screen.getByTitle('Run command')).toBeDefined();
    });

    it('does NOT show Run button for non-shell languages', () => {
        render(<CodeBlock language="typescript" content="echo hello" onRun={() => {}} />);
        expect(screen.queryByTitle('Run command')).toBeNull();
    });

    it('does NOT show Run button when onRun is not provided', () => {
        render(<CodeBlock language="bash" content="echo hello" />);
        expect(screen.queryByTitle('Run command')).toBeNull();
    });

    it('calls onRun with code content when Run button is clicked', () => {
        const onRun = vi.fn();
        render(<CodeBlock language="bash" content="echo hello" onRun={onRun} />);
        fireEvent.click(screen.getByTitle('Run command'));
        expect(onRun).toHaveBeenCalledWith('echo hello');
    });
});
