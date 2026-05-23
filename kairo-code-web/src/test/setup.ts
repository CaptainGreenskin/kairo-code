import '@testing-library/jest-dom';
import { vi } from 'vitest';

// Mock monaco-editor globally — its internal .css imports break vitest's
// node loader and tests never interact with a real editor (jsdom can't
// host one anyway). Components that import monaco get a no-op stub.
vi.mock('monaco-editor', () => ({
    editor: {
        create: () => ({ dispose: () => {}, setValue: () => {}, getValue: () => '', onDidChangeModelContent: () => ({ dispose: () => {} }) }),
        createModel: () => ({ dispose: () => {}, getValue: () => '', setValue: () => {} }),
        createDiffEditor: () => ({ dispose: () => {}, setModel: () => {}, onDidUpdateDiff: () => ({ dispose: () => {} }) }),
        defineTheme: () => {},
        setTheme: () => {},
    },
    Uri: { parse: (s: string) => ({ toString: () => s }) },
    languages: { register: () => {}, setMonarchTokensProvider: () => {} },
}));
