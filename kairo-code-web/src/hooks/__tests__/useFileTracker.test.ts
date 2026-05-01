import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useFileTracker } from '../useFileTracker';

describe('useFileTracker', () => {
    it('starts with no files', () => {
        const { result } = renderHook(() => useFileTracker());
        expect(result.current.files).toHaveLength(0);
    });

    it('tracks a Read tool as op=read', () => {
        const { result } = renderHook(() => useFileTracker());
        act(() => {
            result.current.trackToolCall({
                toolName: 'Read',
                input: { file_path: '/repo/src/foo.ts' },
            });
        });
        expect(result.current.files).toHaveLength(1);
        expect(result.current.files[0].path).toBe('/repo/src/foo.ts');
        expect(result.current.files[0].ops.has('read')).toBe(true);
    });

    it('tracks Write/Edit/Patch tools as op=write', () => {
        const { result } = renderHook(() => useFileTracker());
        act(() => {
            result.current.trackToolCall({ toolName: 'Write', input: { file_path: '/a.ts' } });
            result.current.trackToolCall({ toolName: 'Edit', input: { file_path: '/b.ts' } });
            result.current.trackToolCall({ toolName: 'ApplyPatch', input: { path: '/c.ts' } });
        });
        const ops = result.current.files.map(f => [...f.ops][0]).sort();
        expect(ops).toEqual(['write', 'write', 'write']);
    });

    it('tracks Grep/Glob/Search/Tree tools as op=search and uses pattern field', () => {
        const { result } = renderHook(() => useFileTracker());
        act(() => {
            result.current.trackToolCall({ toolName: 'Grep', input: { pattern: 'src/**/*.ts' } });
            result.current.trackToolCall({ toolName: 'Glob', input: { pattern: '*.md' } });
        });
        expect(result.current.files).toHaveLength(2);
        for (const f of result.current.files) expect(f.ops.has('search')).toBe(true);
    });

    it('merges ops when the same path is touched by different tools', () => {
        const { result } = renderHook(() => useFileTracker());
        act(() => {
            result.current.trackToolCall({ toolName: 'Read', input: { file_path: '/x.ts' } });
            result.current.trackToolCall({ toolName: 'Edit', input: { file_path: '/x.ts' } });
        });
        expect(result.current.files).toHaveLength(1);
        const f = result.current.files[0];
        expect(f.ops.has('read')).toBe(true);
        expect(f.ops.has('write')).toBe(true);
    });

    it('ignores tool calls with unknown tool name or missing path', () => {
        const { result } = renderHook(() => useFileTracker());
        act(() => {
            result.current.trackToolCall({ toolName: 'RunBash', input: { cmd: 'ls' } });
            result.current.trackToolCall({ toolName: 'Read', input: {} });
        });
        expect(result.current.files).toHaveLength(0);
    });

    it('clearFiles empties the list', () => {
        const { result } = renderHook(() => useFileTracker());
        act(() => {
            result.current.trackToolCall({ toolName: 'Read', input: { file_path: '/a.ts' } });
        });
        expect(result.current.files).toHaveLength(1);
        act(() => result.current.clearFiles());
        expect(result.current.files).toHaveLength(0);
    });

    it('returns files sorted by lastSeen desc (most recent first)', async () => {
        const { result } = renderHook(() => useFileTracker());
        act(() => {
            result.current.trackToolCall({ toolName: 'Read', input: { file_path: '/old.ts' } });
        });
        await new Promise(r => setTimeout(r, 5));
        act(() => {
            result.current.trackToolCall({ toolName: 'Read', input: { file_path: '/new.ts' } });
        });
        expect(result.current.files[0].path).toBe('/new.ts');
        expect(result.current.files[1].path).toBe('/old.ts');
    });
});
