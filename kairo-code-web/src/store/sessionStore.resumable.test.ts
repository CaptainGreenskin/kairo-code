import { describe, it, expect, beforeEach } from 'vitest';
import { useSessionStore } from './sessionStore';

/**
 * Covers the `resumable` flag that drives the general-flow "Resume" button:
 * set on Stop, cleared when a new run starts / on SESSION_RESUMED / on restore.
 */
describe('sessionStore resumable flag', () => {
    beforeEach(() => {
        useSessionStore.getState().closeAllSessions();
    });

    it('defaults to false for a fresh session', () => {
        const s = useSessionStore.getState();
        s.openSession('s1');
        expect(useSessionStore.getState().sessions['s1'].resumable).toBe(false);
    });

    it('setResumableFor toggles the flag and mirrors to active session', () => {
        const s = useSessionStore.getState();
        s.openSession('s1');
        s.setResumableFor('s1', true);
        expect(useSessionStore.getState().sessions['s1'].resumable).toBe(true);
        expect(useSessionStore.getState().resumable).toBe(true);
    });

    it('resumable is sticky across running toggles (cleared only by new message / resume / restore)', () => {
        const s = useSessionStore.getState();
        s.openSession('s1');
        s.setResumableFor('s1', true);
        // Late events from a not-yet-halted run may flip running back on; resumable must survive
        // so the Resume affordance still appears once the run finally settles.
        s.setRunningFor('s1', true);
        expect(useSessionStore.getState().sessions['s1'].resumable).toBe(true);
    });

    it('setRunningFor(false) preserves resumable', () => {
        const s = useSessionStore.getState();
        s.openSession('s1');
        s.setResumableFor('s1', true);
        s.setRunningFor('s1', false);
        expect(useSessionStore.getState().sessions['s1'].resumable).toBe(true);
    });

    it('restoreSessionAs preserves resumable across rebinds', () => {
        const s = useSessionStore.getState();
        s.openSession('s1');
        s.setResumableFor('s1', true);
        // Periodic WS rebinds fire SESSION_RESTORED → restoreSessionAs; the Resume affordance
        // for a stopped run must survive them.
        s.restoreSessionAs('s1', [], false);
        expect(useSessionStore.getState().sessions['s1'].resumable).toBe(true);
    });
});
