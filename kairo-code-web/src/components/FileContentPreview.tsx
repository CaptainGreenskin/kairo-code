import { useState } from 'react';
import { ChevronDown, FileCode } from 'lucide-react';
import { LazySyntaxHighlighter } from './LazySyntaxHighlighter';
import type { FileWriteInfo } from '@utils/toolPreview';

const MAX_PREVIEW_LINES = 30;

export function FileContentPreview({ info }: { info: FileWriteInfo }) {
    const [open, setOpen] = useState(false);

    const lines = info.content.split('\n');
    const truncated = lines.length > MAX_PREVIEW_LINES;
    const previewContent = truncated
        ? lines.slice(0, MAX_PREVIEW_LINES).join('\n') + `\n\u2026 (${lines.length - MAX_PREVIEW_LINES} more lines)`
        : info.content;

    return (
        <div className="mt-2 rounded border border-[var(--border)] overflow-hidden">
            <button
                onClick={() => setOpen(v => !v)}
                className="w-full flex items-center gap-2 px-2.5 py-1.5 text-xs bg-[var(--bg-secondary)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
            >
                <FileCode size={11} />
                <span className="flex-1 text-left truncate font-mono">{info.filePath}</span>
                <span className="text-[10px] opacity-60">{lines.length} lines</span>
                <ChevronDown
                    size={11}
                    className={`ml-1 transition-transform ${open ? 'rotate-180' : ''}`}
                />
            </button>
            {open && (
                <div className="max-h-64 overflow-auto bg-[var(--color-code-bg)]">
                    <LazySyntaxHighlighter
                        language={info.language}
                        PreTag="div"
                        customStyle={{
                            margin: 0,
                            borderRadius: 0,
                            background: 'transparent',
                            padding: '0.75rem',
                            fontSize: '11px',
                            lineHeight: '1.5',
                        }}
                        showLineNumbers={false}
                        wrapLines={false}
                    >
                        {previewContent}
                    </LazySyntaxHighlighter>
                </div>
            )}
        </div>
    );
}
