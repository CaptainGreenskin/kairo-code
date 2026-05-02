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

describe('ToolCallCard result expand/collapse (line-based)', () => {
    it('does not show result when toolCall has no result', () => {
        const toolCall = makeToolCall({ result: undefined });
        render(<ToolCallCard toolCall={toolCall} />);
        expect(screen.queryByText(/Show .* more lines/i)).not.toBeInTheDocument();
    });

    it('does not show expand button when result is <=5 lines', () => {
        const shortResult = 'line1\nline2\nline3\nline4\nline5';
        const toolCall = makeToolCall({ result: shortResult });
        render(<ToolCallCard toolCall={toolCall} />);
        expect(screen.queryByText(/Show .* more lines/i)).not.toBeInTheDocument();
    });

    it('shows "Show N more lines" button when result exceeds 5 lines', () => {
        const longResult = Array.from({ length: 10 }, (_, i) => `line${i + 1}`).join('\n');
        const toolCall = makeToolCall({ result: longResult });
        render(<ToolCallCard toolCall={toolCall} />);
        expect(screen.getByText(/Show 5 more lines/i)).toBeInTheDocument();
    });

    it('expands result when "Show N more lines" is clicked', () => {
        const longResult = Array.from({ length: 10 }, (_, i) => `line${i + 1}`).join('\n');
        const toolCall = makeToolCall({ result: longResult });
        render(<ToolCallCard toolCall={toolCall} />);

        // Initially collapsed
        expect(screen.getByText(/Show 5 more lines/i)).toBeInTheDocument();

        // Click to expand
        fireEvent.click(screen.getByText(/Show 5 more lines/i));
        expect(screen.getByText('▴ Collapse')).toBeInTheDocument();
    });

    it('collapses result when "Collapse" is clicked', () => {
        const longResult = Array.from({ length: 10 }, (_, i) => `line${i + 1}`).join('\n');
        const toolCall = makeToolCall({ result: longResult });
        render(<ToolCallCard toolCall={toolCall} />);

        // Expand
        fireEvent.click(screen.getByText(/Show 5 more lines/i));
        expect(screen.getByText('▴ Collapse')).toBeInTheDocument();

        // Collapse
        fireEvent.click(screen.getByText('▴ Collapse'));
        expect(screen.getByText(/Show 5 more lines/i)).toBeInTheDocument();
    });

    it('shows first 5 lines in collapsed state', () => {
        const longResult = Array.from({ length: 10 }, (_, i) => `line_${i + 1}`).join('\n');
        const toolCall = makeToolCall({ result: longResult });
        const { container } = render(<ToolCallCard toolCall={toolCall} />);
        const pre = container.querySelector('pre');
        expect(pre?.textContent).toContain('line_1');
        expect(pre?.textContent).toContain('line_5');
        expect(pre?.textContent).not.toContain('line_6');
    });

    it('shows full text in expanded state', () => {
        const longResult = Array.from({ length: 10 }, (_, i) => `line_${i + 1}`).join('\n');
        const toolCall = makeToolCall({ result: longResult });
        const { container } = render(<ToolCallCard toolCall={toolCall} />);

        fireEvent.click(screen.getByText(/Show 5 more lines/i));
        const pre = container.querySelector('pre');
        expect(pre?.textContent).toBe(longResult);
    });

    it('does not show result region when status is not done', () => {
        const longResult = Array.from({ length: 10 }, (_, i) => `line${i + 1}`).join('\n');
        const toolCall = makeToolCall({ result: longResult, status: 'pending' });
        render(<ToolCallCard toolCall={toolCall} />);
        expect(screen.queryByText(/Show .* more lines/i)).not.toBeInTheDocument();
    });
});

describe('ToolCallCard status indicator dot', () => {
    it('shows amber pulsing dot for pending status', () => {
        const toolCall = makeToolCall({ status: 'pending' });
        const { container } = render(<ToolCallCard toolCall={toolCall} />);
        const dot = container.querySelector('span.w-1.h-1.rounded-full');
        expect(dot).toBeInTheDocument();
        expect(dot?.className).toContain('bg-amber-400');
        expect(dot?.className).toContain('animate-pulse');
    });

    it('shows amber pulsing dot for approved status', () => {
        const toolCall = makeToolCall({ status: 'approved' });
        const { container } = render(<ToolCallCard toolCall={toolCall} />);
        const dot = container.querySelector('span.w-1.h-1.rounded-full');
        expect(dot).toBeInTheDocument();
        expect(dot?.className).toContain('bg-amber-400');
        expect(dot?.className).toContain('animate-pulse');
    });

    it('shows green dot for done status', () => {
        const toolCall = makeToolCall({ status: 'done' });
        const { container } = render(<ToolCallCard toolCall={toolCall} />);
        const dot = container.querySelector('span.w-1.h-1.rounded-full');
        expect(dot).toBeInTheDocument();
        expect(dot?.className).toContain('bg-green-500');
    });

    it('shows red dot for error status', () => {
        const toolCall = makeToolCall({ status: 'error' });
        const { container } = render(<ToolCallCard toolCall={toolCall} />);
        const dot = container.querySelector('span.w-1.h-1.rounded-full');
        expect(dot).toBeInTheDocument();
        expect(dot?.className).toContain('bg-red-500');
    });

    it('shows blue pulsing dot for requires_approval pending', () => {
        const toolCall = makeToolCall({ status: 'pending', requiresApproval: true });
        const { container } = render(<ToolCallCard toolCall={toolCall} />);
        const dot = container.querySelector('span.w-1.h-1.rounded-full');
        expect(dot).toBeInTheDocument();
        expect(dot?.className).toContain('bg-blue-500');
        expect(dot?.className).toContain('animate-pulse');
    });
});

describe('ToolCallCard inline diff detection', () => {
    const unifiedDiff = `--- a/src/index.ts
+++ b/src/index.ts
@@ -1,3 +1,3 @@
 import React from 'react';
-import { Foo } from './foo';
+import { Bar } from './bar';
 export default function App() {`;

    it('shows file path badge for write_file with path input when result is not a diff', () => {
        const toolCall = makeToolCall({
            toolName: 'write_file',
            input: { path: 'src/main.ts' },
            result: 'File written successfully',
        });
        render(<ToolCallCard toolCall={toolCall} />);
        expect(screen.getByText('src/main.ts')).toBeInTheDocument();
    });

    it('shows file path badge for patch_file with path input when result is not a diff', () => {
        const toolCall = makeToolCall({
            toolName: 'patch_file',
            input: { path: 'lib/utils.py' },
            result: 'Patch applied',
        });
        render(<ToolCallCard toolCall={toolCall} />);
        expect(screen.getByText('lib/utils.py')).toBeInTheDocument();
    });

    it('renders diff result for write_file with unified diff content', () => {
        const toolCall = makeToolCall({
            toolName: 'write_file',
            input: { path: 'src/index.ts' },
            result: unifiedDiff,
        });
        render(<ToolCallCard toolCall={toolCall} />);
        // Should show the diff file path extracted from diff header
        expect(screen.getByText('src/index.ts')).toBeInTheDocument();
    });
});
