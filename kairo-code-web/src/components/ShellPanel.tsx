import { useEffect, useRef, useState, useCallback } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { X, Minimize2, Maximize2, RotateCcw } from 'lucide-react';
import '@xterm/xterm/css/xterm.css';

interface ShellPanelProps {
    stompClient: import('@stomp/stompjs').Client | null;
    onClose: () => void;
    externalCommand?: string;
}

const SHELL_TOPIC_PREFIX = '/topic/shell/';
const SHELL_APP_PREFIX = '/app/shell/';

function generateShellId() {
    return 'shell-' + Math.random().toString(36).slice(2, 10);
}

export function ShellPanel({ stompClient, onClose, externalCommand }: ShellPanelProps) {
    const termRef = useRef<HTMLDivElement>(null);
    const termInstance = useRef<Terminal | null>(null);
    const fitAddon = useRef<FitAddon | null>(null);
    const shellIdRef = useRef<string>(generateShellId());
    const lineBufferRef = useRef('');
    const [isMaximized, setIsMaximized] = useState(false);
    const [panelHeight, setPanelHeight] = useState(280);
    const subscriptions = useRef<import('@stomp/stompjs').StompSubscription[]>([]);

    const writeToTerm = useCallback((text: string) => {
        termInstance.current?.write(text);
    }, []);

    // Initialize xterm
    useEffect(() => {
        if (!termRef.current) return;
        const term = new Terminal({
            theme: {
                background: '#0d0d0d',
                foreground: '#d4d4d4',
                cursor: '#d4d4d4',
                selectionBackground: '#264f78',
            },
            fontFamily: '"JetBrains Mono", "Fira Code", "Cascadia Code", monospace',
            fontSize: 12,
            lineHeight: 1.4,
            cursorBlink: true,
            convertEol: true,
        });
        const fit = new FitAddon();
        term.loadAddon(fit);
        term.open(termRef.current);
        fit.fit();
        termInstance.current = term;
        fitAddon.current = fit;

        // Handle user input — accumulate until Enter
        term.onData((data) => {
            if (data === '\r') {
                // Enter pressed — send line
                const line = lineBufferRef.current;
                lineBufferRef.current = '';
                term.write('\r\n');
                if (stompClient?.connected) {
                    stompClient.publish({
                        destination: SHELL_APP_PREFIX + 'input',
                        body: JSON.stringify({ shellId: shellIdRef.current, line }),
                    });
                }
            } else if (data === '\x7f') {
                // Backspace
                if (lineBufferRef.current.length > 0) {
                    lineBufferRef.current = lineBufferRef.current.slice(0, -1);
                    term.write('\b \b');
                }
            } else if (data >= ' ') {
                lineBufferRef.current += data;
                term.write(data);
            }
        });

        return () => { term.dispose(); };
    }, [stompClient]);

    // STOMP subscriptions + shell create
    useEffect(() => {
        if (!stompClient?.connected) return;
        const shellId = shellIdRef.current;

        const outSub = stompClient.subscribe(
            SHELL_TOPIC_PREFIX + shellId + '/out',
            (msg) => {
                const { line } = JSON.parse(msg.body) as { line: string; isError: boolean };
                writeToTerm(line + '\r\n');
            },
        );
        const metaSub = stompClient.subscribe(
            SHELL_TOPIC_PREFIX + shellId + '/meta',
            (msg) => {
                const { type, exitCode } = JSON.parse(msg.body) as { type: string; exitCode?: number };
                if (type === 'ready') {
                    writeToTerm('\x1b[32m● Shell ready\x1b[0m\r\n');
                } else if (type === 'exit') {
                    writeToTerm(`\r\n\x1b[33m[Process exited with code ${exitCode}]\x1b[0m\r\n`);
                }
            },
        );
        subscriptions.current = [outSub, metaSub];

        stompClient.publish({
            destination: SHELL_APP_PREFIX + 'create',
            body: JSON.stringify({ shellId }),
        });

        return () => {
            subscriptions.current.forEach(s => s.unsubscribe());
            stompClient.publish({
                destination: SHELL_APP_PREFIX + 'close',
                body: JSON.stringify({ shellId }),
            });
        };
    }, [stompClient, writeToTerm]);

    // Resize observer
    useEffect(() => {
        if (!termRef.current) return;
        const ro = new ResizeObserver(() => fitAddon.current?.fit());
        ro.observe(termRef.current);
        return () => ro.disconnect();
    }, []);

    // Handle external commands (e.g., from code block Run button)
    const prevExternalCommand = useRef<string | undefined>(undefined);
    useEffect(() => {
        if (!externalCommand || externalCommand === prevExternalCommand.current) return;
        prevExternalCommand.current = externalCommand;
        if (stompClient?.connected) {
            const line = externalCommand;
            termInstance.current?.writeln(line);
            stompClient.publish({
                destination: SHELL_APP_PREFIX + 'input',
                body: JSON.stringify({ shellId: shellIdRef.current, line }),
            });
        }
    }, [externalCommand, stompClient]);

    const handleRestart = useCallback(() => {
        if (!stompClient?.connected) return;
        const old = shellIdRef.current;
        stompClient.publish({ destination: SHELL_APP_PREFIX + 'close', body: JSON.stringify({ shellId: old }) });
        shellIdRef.current = generateShellId();
        termInstance.current?.clear();
        // Re-subscribe with new shellId
        const shellId = shellIdRef.current;
        subscriptions.current.forEach(s => s.unsubscribe());
        const outSub = stompClient.subscribe(
            SHELL_TOPIC_PREFIX + shellId + '/out',
            (msg) => {
                const { line } = JSON.parse(msg.body) as { line: string };
                writeToTerm(line + '\r\n');
            },
        );
        const metaSub = stompClient.subscribe(
            SHELL_TOPIC_PREFIX + shellId + '/meta',
            (msg) => {
                const { type } = JSON.parse(msg.body) as { type: string };
                if (type === 'ready') writeToTerm('\x1b[32m● Shell ready\x1b[0m\r\n');
            },
        );
        subscriptions.current = [outSub, metaSub];
        stompClient.publish({ destination: SHELL_APP_PREFIX + 'create', body: JSON.stringify({ shellId }) });
    }, [stompClient, writeToTerm]);

    // Drag-to-resize handle
    const handleDragStart = useCallback((e: React.MouseEvent) => {
        const startY = e.clientY;
        const startH = panelHeight;
        const onMove = (mv: MouseEvent) => {
            const newH = Math.max(120, Math.min(600, startH - (mv.clientY - startY)));
            setPanelHeight(newH);
            fitAddon.current?.fit();
        };
        const onUp = () => {
            window.removeEventListener('mousemove', onMove);
            window.removeEventListener('mouseup', onUp);
        };
        window.addEventListener('mousemove', onMove);
        window.addEventListener('mouseup', onUp);
    }, [panelHeight]);

    const height = isMaximized ? 'calc(100vh - 60px)' : panelHeight;

    return (
        <div
            className="fixed bottom-0 left-0 right-0 z-40 flex flex-col border-t border-[var(--border)] bg-[#0d0d0d] shadow-2xl"
            style={{ height }}
        >
            {/* Drag handle */}
            <div
                className="h-1 cursor-row-resize bg-[var(--border)] hover:bg-[var(--accent)] transition-colors shrink-0"
                onMouseDown={handleDragStart}
            />

            {/* Header */}
            <div className="flex items-center justify-between px-3 py-1.5 bg-[var(--bg-secondary)] border-b border-[var(--border)] shrink-0">
                <span className="text-xs font-mono text-[var(--text-muted)]">bash — {shellIdRef.current}</span>
                <div className="flex items-center gap-1">
                    <button
                        onClick={handleRestart}
                        className="p-1 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                        title="New shell"
                    >
                        <RotateCcw size={12} />
                    </button>
                    <button
                        onClick={() => { setIsMaximized(m => !m); setTimeout(() => fitAddon.current?.fit(), 50); }}
                        className="p-1 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                        title={isMaximized ? 'Restore' : 'Maximize'}
                    >
                        {isMaximized ? <Minimize2 size={12} /> : <Maximize2 size={12} />}
                    </button>
                    <button
                        onClick={onClose}
                        className="p-1 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] hover:text-red-400 transition-colors"
                        title="Close"
                    >
                        <X size={12} />
                    </button>
                </div>
            </div>

            {/* Terminal */}
            <div ref={termRef} className="flex-1 overflow-hidden" />
        </div>
    );
}
