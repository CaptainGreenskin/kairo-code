import { useState, useCallback } from 'react';
import type { ToolCall } from '@/types/agent';

export type FileOp = 'read' | 'write' | 'search';

export interface TrackedFile {
    path: string;
    ops: Set<FileOp>;
    lastSeen: number;
}

/** Accumulates files touched by agent tools in the current session. */
export function useFileTracker() {
    const [files, setFiles] = useState<Map<string, TrackedFile>>(new Map());

    const trackToolCall = useCallback((toolCall: Pick<ToolCall, 'toolName' | 'input'>) => {
        const op = inferOp(toolCall.toolName);
        if (!op) return;
        const path = extractPath(toolCall.input);
        if (!path) return;

        setFiles(prev => {
            const next = new Map(prev);
            const existing = next.get(path);
            if (existing) {
                const newOps = new Set(existing.ops);
                newOps.add(op);
                next.set(path, { ...existing, ops: newOps, lastSeen: Date.now() });
            } else {
                next.set(path, { path, ops: new Set([op]), lastSeen: Date.now() });
            }
            return next;
        });
    }, []);

    const clearFiles = useCallback(() => setFiles(new Map()), []);

    const fileList = [...files.values()].sort((a, b) => b.lastSeen - a.lastSeen);

    return { files: fileList, trackToolCall, clearFiles };
}

function inferOp(toolName: string): FileOp | null {
    const name = toolName.toLowerCase();
    if (name.includes('write') || name.includes('edit') || name.includes('patch')) return 'write';
    if (name.includes('read') || name.includes('view')) return 'read';
    if (name.includes('grep') || name.includes('glob') || name.includes('search') || name.includes('tree')) return 'search';
    return null;
}

function extractPath(input: Record<string, unknown> | undefined): string | null {
    if (!input) return null;
    const candidates = ['file_path', 'path', 'filePath', 'pattern'];
    for (const key of candidates) {
        const val = input[key];
        if (typeof val === 'string' && val.length > 0) return val;
    }
    return null;
}
