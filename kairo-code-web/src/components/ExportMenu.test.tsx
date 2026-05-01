import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ExportMenu } from './ExportMenu';

describe('ExportMenu', () => {
    it('renders export download button', () => {
        render(<ExportMenu onExport={vi.fn()} />);
        const btn = document.querySelector('button[title="Export session"]');
        expect(btn).not.toBeNull();
    });

    it('is disabled when disabled=true', () => {
        render(<ExportMenu onExport={vi.fn()} disabled />);
        const btn = document.querySelector('button[title="Export session"]') as HTMLButtonElement;
        expect(btn.disabled).toBe(true);
    });

    it('opens dropdown on button click', () => {
        render(<ExportMenu onExport={vi.fn()} />);
        const trigger = document.querySelector('button[title="Export session"]')!;
        fireEvent.click(trigger);
        expect(screen.getByText('Export as Markdown')).toBeDefined();
        expect(screen.getByText('Export as JSON')).toBeDefined();
    });

    it('calls onExport with markdown when markdown clicked', () => {
        const onExport = vi.fn();
        render(<ExportMenu onExport={onExport} />);
        fireEvent.click(document.querySelector('button[title="Export session"]')!);
        fireEvent.click(screen.getByText('Export as Markdown'));
        expect(onExport).toHaveBeenCalledWith('markdown');
    });

    it('calls onExport with json when json clicked', () => {
        const onExport = vi.fn();
        render(<ExportMenu onExport={onExport} />);
        fireEvent.click(document.querySelector('button[title="Export session"]')!);
        fireEvent.click(screen.getByText('Export as JSON'));
        expect(onExport).toHaveBeenCalledWith('json');
    });

    it('calls onExportSuccess after export', () => {
        const onExport = vi.fn();
        const onExportSuccess = vi.fn();
        render(<ExportMenu onExport={onExport} onExportSuccess={onExportSuccess} />);
        fireEvent.click(document.querySelector('button[title="Export session"]')!);
        fireEvent.click(screen.getByText('Export as Markdown'));
        expect(onExportSuccess).toHaveBeenCalledWith('markdown');
    });

    it('calls onCopy when copy option clicked', () => {
        const onCopy = vi.fn();
        render(<ExportMenu onExport={vi.fn()} onCopy={onCopy} />);
        fireEvent.click(document.querySelector('button[title="Export session"]')!);
        fireEvent.click(screen.getByText('Copy as Markdown'));
        expect(onCopy).toHaveBeenCalled();
    });

    it('closes dropdown after clicking backdrop', () => {
        render(<ExportMenu onExport={vi.fn()} />);
        fireEvent.click(document.querySelector('button[title="Export session"]')!);
        expect(screen.getByText('Export as Markdown')).toBeDefined();
        // Click the backdrop (fixed overlay)
        const backdrop = document.querySelector('.fixed.inset-0.z-40');
        if (backdrop) {
            fireEvent.click(backdrop);
            expect(screen.queryByText('Export as Markdown')).toBeNull();
        }
    });

    it('does not show copy option when onCopy not provided', () => {
        render(<ExportMenu onExport={vi.fn()} />);
        fireEvent.click(document.querySelector('button[title="Export session"]')!);
        expect(screen.queryByText('Copy as Markdown')).toBeNull();
    });
});
