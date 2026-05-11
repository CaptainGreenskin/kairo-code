import { useEffect, useRef, useState, useCallback } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { X, Minimize2, Maximize2, RotateCcw } from 'lucide-react';
import '@xterm/xterm/css/xterm.css';

interface ShellPanelProps {
    onClose: () => void;
    externalCommand?: string;
    workspaceId?: string;
    /** When true, skip fixed-modal wrapper and the internal drag handle — host (BottomPanel) controls layout. */
    embedded?: boolean;
}

export function ShellPanel({ onClose, externalCommand, workspaceId, embedded = false }: ShellPanelProps) {
    const termRef = useRef<HTMLDivElement>(null);
    const termInstance = useRef<Terminal | null>(null);
    const fitAddon = useRef<FitAddon | null>(null);
    const wsRef = useRef<WebSocket | null>(null);
    const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const userClosedRef = useRef(false);
    const [isMaximized, setIsMaximized] = useState(false);
    const [panelHeight, setPanelHeight] = useState(280);

    // Connect WebSocket to /ws/shell — refuses if a non-closed socket already exists
    const connectWs = useCallback(() => {
        // Dedupe: if a WS is already alive, reuse it (handles StrictMode double-mount).
        const existing = wsRef.current;
        if (existing && (existing.readyState === WebSocket.OPEN || existing.readyState === WebSocket.CONNECTING)) {
            return;
        }
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const qs = workspaceId ? `?workspaceId=${encodeURIComponent(workspaceId)}` : '';
        const ws = new WebSocket(`${protocol}//${window.location.host}/ws/shell${qs}`);
        wsRef.current = ws;

        ws.onopen = () => {
            termInstance.current?.write('\x1b[32m● Shell connected\x1b[0m\r\n');
        };

        ws.onmessage = (event) => {
            try {
                const msg = JSON.parse(event.data);
                if (msg.type === 'data') {
                    termInstance.current?.write(msg.data);
                } else if (msg.type === 'exit') {
                    termInstance.current?.write(
                        `\r\n\x1b[33m[Process exited with code ${msg.exitCode}]\x1b[0m\r\n`
                    );
                }
            } catch {
                // ignore parse errors
            }
        };

        ws.onclose = () => {
            if (wsRef.current === ws) wsRef.current = null;
            if (userClosedRef.current) return;
            // Auto-reconnect after 2s — but only if user hasn't closed and component still mounted
            if (reconnectTimerRef.current) clearTimeout(reconnectTimerRef.current);
            reconnectTimerRef.current = setTimeout(() => {
                reconnectTimerRef.current = null;
                if (!userClosedRef.current && termRef.current) connectWs();
            }, 2000);
        };
    }, [workspaceId]);

    // Initialize xterm + connect WebSocket on mount
    useEffect(() => {
        if (!termRef.current) return;
        userClosedRef.current = false;

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

        // User input: send raw bytes to shell via WebSocket
        term.onData((data) => {
            if (wsRef.current?.readyState === WebSocket.OPEN) {
                wsRef.current.send(JSON.stringify({ type: 'input', data }));
            }
        });

        connectWs();

        return () => {
            userClosedRef.current = true;
            if (reconnectTimerRef.current) {
                clearTimeout(reconnectTimerRef.current);
                reconnectTimerRef.current = null;
            }
            const ws = wsRef.current;
            if (ws) {
                ws.onclose = null;  // suppress reconnect-on-unmount race
                ws.close();
                wsRef.current = null;
            }
            term.dispose();
            termInstance.current = null;
            fitAddon.current = null;
        };
    }, [connectWs]);

    // Resize observer
    useEffect(() => {
        if (!termRef.current) return;
        const ro = new ResizeObserver(() => fitAddon.current?.fit());
        ro.observe(termRef.current);
        return () => ro.disconnect();
    }, []);

    // Handle external command injection
    useEffect(() => {
        if (!externalCommand) return;
        if (wsRef.current?.readyState === WebSocket.OPEN) {
            termInstance.current?.writeln(externalCommand);
            wsRef.current.send(JSON.stringify({ type: 'input', data: externalCommand + '\n' }));
        }
    }, [externalCommand]);

    // Restart: close current WS and reconnect
    const handleRestart = useCallback(() => {
        const ws = wsRef.current;
        if (ws) {
            ws.onclose = null;  // suppress auto-reconnect for THIS connection
            ws.close();
            wsRef.current = null;
        }
        if (reconnectTimerRef.current) {
            clearTimeout(reconnectTimerRef.current);
            reconnectTimerRef.current = null;
        }
        termInstance.current?.clear();
        connectWs();
    }, [connectWs]);

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

    if (embedded) {
        return (
            <div className="flex flex-col h-full bg-[#0d0d0d]">
                <div className="flex items-center justify-between px-3 py-1.5 bg-[var(--bg-secondary)] border-b border-[var(--border)] shrink-0">
                    <span className="text-xs font-mono text-[var(--text-muted)]">bash</span>
                    <div className="flex items-center gap-1">
                        <button
                            onClick={handleRestart}
                            className="p-1 rounded hover:bg-[var(--bg-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                            title="New shell"
                        >
                            <RotateCcw size={12} />
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
                <div ref={termRef} className="flex-1 overflow-hidden" />
            </div>
        );
    }

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
                <span className="text-xs font-mono text-[var(--text-muted)]">bash</span>
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
