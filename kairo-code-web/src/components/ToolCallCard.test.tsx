import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { ToolCallCard } from '@/components/ToolCallCard';
import type { ToolCall } from '@/types/agent';

function makeToolCall(overrides: Partial<ToolCall> = {}): ToolCall {
    return {
        id: 'tc-1',
        toolName: 'read_file',
        input: { path: 'src/index.ts' },
        status: 'done',
        requiresApproval: false,
        ...overrides,
    };
}

describe('ToolCallCard result expand/collapse', () => {
    it('does not show result when toolCall has no result', () => {
        const toolCall = makeToolCall({ result: undefined });
        render(<ToolCallCard toolCall={toolCall} />);
        expect(screen.queryByText(/Show more/i)).not.toBeInTheDocument();
    });

    it('does not show expand button when result is short (<=200 chars)', () => {
        const shortResult = 'ok'.repeat(50); // 100 chars
        const toolCall = makeToolCall({ result: shortResult });
        render(<ToolCallCard toolCall={toolCall} />);
        expect(screen.queryByText(/Show more/i)).not.toBeInTheDocument();
    });

    it('shows "Show more" button when result exceeds 200 chars', () => {
        const longResult = 'x'.repeat(300);
        const toolCall = makeToolCall({ result: longResult });
        render(<ToolCallCard toolCall={toolCall} />);
        expect(screen.getByText(/Show 100 more chars/i)).toBeInTheDocument();
    });

    it('expands result when "Show more" is clicked', () => {
        const longResult = 'x'.repeat(300);
        const toolCall = makeToolCall({ result: longResult });
        render(<ToolCallCard toolCall={toolCall} />);

        // Initially collapsed
        expect(screen.getByText(/Show 100 more chars/i)).toBeInTheDocument();

        // Click to expand
        fireEvent.click(screen.getByText(/Show 100 more chars/i));
        expect(screen.getByText('Show less')).toBeInTheDocument();
    });

    it('collapses result when "Show less" is clicked', () => {
        const longResult = 'x'.repeat(300);
        const toolCall = makeToolCall({ result: longResult });
        render(<ToolCallCard toolCall={toolCall} />);

        // Expand
        fireEvent.click(screen.getByText(/Show 100 more chars/i));
        expect(screen.getByText('Show less')).toBeInTheDocument();

        // Collapse
        fireEvent.click(screen.getByText('Show less'));
        expect(screen.getByText(/Show 100 more chars/i)).toBeInTheDocument();
    });

    it('shows preview text in collapsed state', () => {
        const longResult = 'hello world ' + 'x'.repeat(288); // 300 chars
        const tc = makeToolCall({ result: longResult });
        const { container } = render(<ToolCallCard toolCall={tc} />);
        const pre = container.querySelector('pre');
        expect(pre?.textContent).toContain('hello world');
    });

    it('shows full text in expanded state', () => {
        const longResult = 'unique_prefix_' + 'x'.repeat(286); // 300 chars
        const toolCall = makeToolCall({ result: longResult });
        const { container } = render(<ToolCallCard toolCall={toolCall} />);

        fireEvent.click(screen.getByText(/Show \d+ more chars/i));
        const pre = container.querySelector('pre');
        expect(pre?.textContent).toContain('unique_prefix_');
        expect(pre?.textContent).toBe(longResult);
    });

    it('does not show result region when status is not done', () => {
        const longResult = 'x'.repeat(300);
        const toolCall = makeToolCall({ result: longResult, status: 'pending' });
        render(<ToolCallCard toolCall={toolCall} />);
        expect(screen.queryByText(/Show more/i)).not.toBeInTheDocument();
    });
});
