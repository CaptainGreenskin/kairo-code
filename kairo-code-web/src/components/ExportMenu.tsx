import { useState } from 'react';
import { Download, FileText, FileJson } from 'lucide-react';

interface ExportMenuProps {
    onExport: (format: 'markdown' | 'json') => void;
    onExportSuccess?: (format: 'markdown' | 'json') => void;
    disabled?: boolean;
}

export function ExportMenu({ onExport, onExportSuccess, disabled }: ExportMenuProps) {
    const [open, setOpen] = useState(false);
    return (
        <div className="relative">
            <button
                onClick={() => setOpen(v => !v)}
                disabled={disabled}
                className="p-1.5 rounded text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-secondary)] disabled:opacity-40 transition-colors"
                title="Export session"
            >
                <Download size={15} />
            </button>
            {open && (
                <>
                    <div className="fixed inset-0 z-40" onClick={() => setOpen(false)} />
                    <div className="absolute right-0 top-full mt-1 z-50 w-44 rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] shadow-lg overflow-hidden">
                        <button
                            onClick={() => { onExport('markdown'); onExportSuccess?.('markdown'); setOpen(false); }}
                            className="w-full flex items-center gap-2 px-3 py-2.5 text-xs text-[var(--text-secondary)] hover:bg-[var(--bg-secondary)] hover:text-[var(--text-primary)] transition-colors"
                        >
                            <FileText size={13} /> Export as Markdown
                        </button>
                        <button
                            onClick={() => { onExport('json'); onExportSuccess?.('json'); setOpen(false); }}
                            className="w-full flex items-center gap-2 px-3 py-2.5 text-xs text-[var(--text-secondary)] hover:bg-[var(--bg-secondary)] hover:text-[var(--text-primary)] transition-colors"
                        >
                            <FileJson size={13} /> Export as JSON
                        </button>
                    </div>
                </>
            )}
        </div>
    );
}
