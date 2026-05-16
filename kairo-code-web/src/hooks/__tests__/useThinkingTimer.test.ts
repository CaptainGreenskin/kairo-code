import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useThinkingTimer } from '../../hooks/useThinkingTimer';

describe('useThinkingTimer', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // ── Null startTime ─────────────────────────────────────────────────────────

  it('returns empty string when startTime is null', () => {
    const { result } = renderHook(() => useThinkingTimer(null, false));
    expect(result.current).toBe('');
  });

  it('returns empty string when startTime is null even if isActive is true', () => {
    const { result } = renderHook(() => useThinkingTimer(null, true));
    expect(result.current).toBe('');
  });

  // ── Active thinking ────────────────────────────────────────────────────────

  it('returns elapsed seconds when active', () => {
    const now = Date.now();
    vi.setSystemTime(now);

    const startTime = now - 5000; // started 5 seconds ago
    const { result } = renderHook(() => useThinkingTimer(startTime, true));

    // Should show "5s" immediately
    expect(result.current).toBe('5s');
  });

  it('updates elapsed when timer fires', () => {
    const now = Date.now();
    vi.setSystemTime(now);

    const startTime = now - 3000; // started 3 seconds ago
    const { result } = renderHook(() => useThinkingTimer(startTime, true));

    expect(result.current).toBe('3s');

    // Advance by 2 seconds
    act(() => {
      vi.advanceTimersByTime(2000);
    });

    expect(result.current).toBe('5s');
  });

  // ── Inactive (stopped) ────────────────────────────────────────────────────

  it('returns a duration string when isActive becomes false', () => {
    const now = Date.now();
    vi.setSystemTime(now);

    const startTime = now - 10000; // started 10 seconds ago
    const { result } = renderHook(() => useThinkingTimer(startTime, false));

    // Should compute final duration: Math.floor((now - startTime) / 1000) = 10
    expect(result.current).toBe('10s');
  });

  it('does not set up interval when isActive is false', () => {
    const now = Date.now();
    vi.setSystemTime(now);
    const startTime = now - 5000;

    const { result } = renderHook(() => useThinkingTimer(startTime, false));
    const initial = result.current;

    // Advance time — should NOT update since no interval is set
    act(() => {
      vi.advanceTimersByTime(3000);
    });

    // Value should remain the same (the static computation)
    expect(result.current).toBe(initial);
  });

  // ── Transition from active to inactive ────────────────────────────────────

  it('stops updating when isActive changes from true to false', () => {
    const now = Date.now();
    vi.setSystemTime(now);

    const startTime = now - 2000;
    const { result, rerender } = renderHook(
      ({ active }: { active: boolean }) => useThinkingTimer(startTime, active),
      { initialProps: { active: true } },
    );

    expect(result.current).toBe('2s');

    // Advance 1 second while active
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(result.current).toBe('3s');

    // Switch to inactive
    rerender({ active: false });

    // Should return static duration based on Date.now()
    // The effect cleanup ran, so no more interval updates
    const afterDeactivation = result.current;

    act(() => {
      vi.advanceTimersByTime(5000);
    });

    // Should stay the same (no interval firing)
    expect(result.current).toBe(afterDeactivation);
  });
});
