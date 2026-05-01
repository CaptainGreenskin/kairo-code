import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
    saveSnapshot,
    loadSnapshot,
    listSnapshots,
    deleteSnapshot,
    setLastSessionId,
    getLastSessionId,
    clearLastSessionId,
} from '../sessionSnapshot';
import type { Message } from '@/types/agent';

const mockFetch = vi.fn();
// eslint-disable-next-line @typescript-eslint/no-explicit-any
(global as any).fetch = mockFetch;

const sampleMessages: Message[] = [
    { id: 'm1', role: 'user', content: 'hello', toolCalls: [], timestamp: 1 },
];

describe('sessionSnapshot', () => {
    beforeEach(() => {
        mockFetch.mockReset();
        localStorage.clear();
    });

    it('saves snapshot via POST with correct body', async () => {
        mockFetch.mockResolvedValue({ ok: true });
        const ok = await saveSnapshot('sess-1', 'My Session', sampleMessages);
        expect(ok).toBe(true);

        expect(mockFetch).toHaveBeenCalledWith(
            '/api/sessions/sess-1/snapshot',
            expect.objectContaining({
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
            }),
        );
        const body = JSON.parse(mockFetch.mock.calls[0][1].body);
        expect(body).toEqual({
            sessionId: 'sess-1',
            name: 'My Session',
            messages: sampleMessages,
        });
    });

    it('saveSnapshot returns false on empty inputs without calling fetch', async () => {
        expect(await saveSnapshot('', 'name', sampleMessages)).toBe(false);
        expect(await saveSnapshot('sess-1', 'name', [])).toBe(false);
        expect(mockFetch).not.toHaveBeenCalled();
    });

    it('saveSnapshot returns false on network error', async () => {
        mockFetch.mockRejectedValue(new Error('net'));
        expect(await saveSnapshot('sess-1', 'name', sampleMessages)).toBe(false);
    });

    it('saveSnapshot returns false on non-2xx response', async () => {
        mockFetch.mockResolvedValue({ ok: false });
        expect(await saveSnapshot('sess-1', 'name', sampleMessages)).toBe(false);
    });

    it('returns null when snapshot not found', async () => {
        mockFetch.mockResolvedValue({ ok: false });
        const snap = await loadSnapshot('nonexistent');
        expect(snap).toBeNull();
    });

    it('loads snapshot on 200', async () => {
        const data = {
            sessionId: 'sess-1',
            name: 'Test',
            savedAt: 1000,
            messages: sampleMessages,
        };
        mockFetch.mockResolvedValue({ ok: true, json: async () => data });
        const snap = await loadSnapshot('sess-1');
        expect(snap?.sessionId).toBe('sess-1');
        expect(snap?.messages).toHaveLength(1);
    });

    it('loadSnapshot returns null on network error', async () => {
        mockFetch.mockRejectedValue(new Error('boom'));
        expect(await loadSnapshot('sess-1')).toBeNull();
    });

    it('loadSnapshot returns null for empty id without calling fetch', async () => {
        expect(await loadSnapshot('')).toBeNull();
        expect(mockFetch).not.toHaveBeenCalled();
    });

    it('lists snapshots on 200', async () => {
        const metas = [
            { sessionId: 's2', name: 'B', savedAt: 2000, messageCount: 3 },
            { sessionId: 's1', name: 'A', savedAt: 1000, messageCount: 1 },
        ];
        mockFetch.mockResolvedValue({ ok: true, json: async () => metas });
        const list = await listSnapshots();
        expect(list).toEqual(metas);
        expect(mockFetch).toHaveBeenCalledWith('/api/sessions/snapshots');
    });

    it('returns empty array on network error for listSnapshots', async () => {
        mockFetch.mockRejectedValue(new Error('net'));
        expect(await listSnapshots()).toEqual([]);
    });

    it('returns empty array on non-2xx for listSnapshots', async () => {
        mockFetch.mockResolvedValue({ ok: false });
        expect(await listSnapshots()).toEqual([]);
    });

    it('deletes snapshot via DELETE', async () => {
        mockFetch.mockResolvedValue({ ok: true });
        const ok = await deleteSnapshot('sess-1');
        expect(ok).toBe(true);
        expect(mockFetch).toHaveBeenCalledWith(
            '/api/sessions/sess-1/snapshot',
            expect.objectContaining({ method: 'DELETE' }),
        );
    });

    it('deleteSnapshot returns false on network error', async () => {
        mockFetch.mockRejectedValue(new Error('net'));
        expect(await deleteSnapshot('sess-1')).toBe(false);
    });

    it('deleteSnapshot returns false for empty id without calling fetch', async () => {
        expect(await deleteSnapshot('')).toBe(false);
        expect(mockFetch).not.toHaveBeenCalled();
    });

    it('stores and retrieves last session id', () => {
        setLastSessionId('abc-123');
        expect(getLastSessionId()).toBe('abc-123');
    });

    it('clears last session id', () => {
        setLastSessionId('abc-123');
        clearLastSessionId();
        expect(getLastSessionId()).toBeNull();
    });

    it('returns null when no last session id is stored', () => {
        expect(getLastSessionId()).toBeNull();
    });
});
