import { create } from 'zustand';

interface EvolutionState {
    reviewing: boolean;
    skillCount: number;
    setReviewing: (v: boolean) => void;
    setSkillCount: (n: number) => void;
    incrementSkillCount: () => void;
}

export const useEvolutionStore = create<EvolutionState>((set) => ({
    reviewing: false,
    skillCount: 0,
    setReviewing: (v) => set({ reviewing: v }),
    setSkillCount: (n) => set({ skillCount: n }),
    incrementSkillCount: () => set((s) => ({ skillCount: s.skillCount + 1 })),
}));
