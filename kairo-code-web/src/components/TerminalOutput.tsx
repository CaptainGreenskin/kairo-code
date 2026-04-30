import { useEffect, useRef, useState, useCallback } from 'react';
import { ChevronDown, ChevronUp, Copy } from 'lucide-react';

const COLLAPSE_THRESHOLD = 20;

interface TerminalOutputProps {
    output: string;
}

export function TerminalOutput({ output }: TerminalOutputProps) {
    const terminalRef = useRef<HTMLDivElement>(null);
    const [collapsed, setCollapsed] = useState(true);
    const [copied, setCopied] = useState(false);

    const lineCount = output.split('\n').length;
    const shouldCollapse = lineCount > COLLAPSE_THRESHOLD;

    useEffect(() => {
        let cancelled = false;
        let term: import('@xterm/xterm').Terminal | null = null;
        let fitAddon: import('@xterm/addon-fit').FitAddon | null = null;

        (async () => {
            const [{ Terminal }, { FitAddon }] = await Promise.all([
                import('@xterm/xterm'),
                import('@xterm/addon-fit'),
            ]);

            if (cancelled || !terminalRef.current) return;

            term = new Terminal({
                disableStdin: true,
                cursorBlink: false,
                fontSize: 12,
                fontFamily: 'var(--font-mono, ui-monospace, SF Mono, Monaco, monospace)',
                allowProposedApi: true,
                scrollback: 1000,
            });

            fitAddon = new FitAddon();
            term.loadAddon(fitAddon);
            term.open(terminalRef.current);
            fitAddon.fit();

            // Write output (xterm handles ANSI escape codes)
            term.write(output);

            // Refit after content is written
            requestAnimationFrame(() => fitAddon?.fit());
        })();

        return () => {
            cancelled = true;
            term?.dispose();
        };
    }, [output]);

    const handleCopy = useCallback(() => {
        navigator.clipboard.writeText(output);
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
    }, [output]);

    return (
        <div className="border border-[var(--border)] rounded-lg overflow-hidden bg-[var(--code-bg)]">
            <div className="flex items-center justify-between px-3 py-1.5 border-b border-[var(--border)] bg-[var(--bg-secondary)]">
                <span className="text-xs text-[var(--text-muted)]">
                    {lineCount} line{lineCount !== 1 ? 's' : ''}
                </span>
                <div className="flex items-center gap-1">
                    <button
                        onClick={handleCopy}
                        className="p-1 text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                        title="Copy output"
                    >
                        <Copy size={14} />
                        {copied && (
                            <span className="ml-1 text-[10px] text-[var(--color-success)]">
                                Copied!
                            </span>
                        )}
                    </button>
                    {shouldCollapse && (
                        <button
                            onClick={() => setCollapsed((prev) => !prev)}
                            className="p-1 text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                            title={collapsed ? 'Expand' : 'Collapse'}
                        >
                            {collapsed ? <ChevronDown size={14} /> : <ChevronUp size={14} />}
                        </button>
                    )}
                </div>
            </div>

            {(!shouldCollapse || !collapsed) && (
                <div
                    ref={terminalRef}
                    className={collapsed && shouldCollapse ? '' : ''}
                    style={{
                        minHeight: '40px',
                        maxHeight: shouldCollapse && !collapsed ? '400px' : 'none',
                        overflow: 'auto',
                    }}
                />
            )}
        </div>
    );
}
