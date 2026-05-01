import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { usePlanSteps } from '../usePlanSteps';

describe('usePlanSteps', () => {
    it('initializes with empty steps', () => {
        const { result } = renderHook(() => usePlanSteps([]));
        expect(result.current.steps).toHaveLength(0);
    });

    it('setPlanSteps populates steps', () => {
        const { result } = renderHook(() => usePlanSteps([]));
        act(() => result.current.setPlanSteps(['Step A', 'Step B', 'Step C']));
        expect(result.current.steps).toHaveLength(3);
        expect(result.current.steps[0]).toEqual({ text: 'Step A', done: false });
    });

    it('markStepDone marks correct step', () => {
        const { result } = renderHook(() => usePlanSteps([]));
        act(() => result.current.setPlanSteps(['S1', 'S2']));
        act(() => result.current.markStepDone(0));
        expect(result.current.steps[0].done).toBe(true);
        expect(result.current.steps[1].done).toBe(false);
    });

    it('parses numbered steps from assistant messages', () => {
        const messages = [
            { role: 'assistant', content: 'Plan:\n1. First step\n2. Second step\n3. Third step' },
        ];
        const { result } = renderHook(() => usePlanSteps(messages));
        expect(result.current.steps).toHaveLength(3);
    });

    it('clearPlan resets steps', () => {
        const { result } = renderHook(() => usePlanSteps([]));
        act(() => result.current.setPlanSteps(['A', 'B']));
        act(() => result.current.clearPlan());
        expect(result.current.steps).toHaveLength(0);
    });
});
