/**
 * Initialise monaco-vscode-api services. Must be awaited BEFORE the first
 * `monaco.editor.create(...)` call. See main.tsx for the boot sequence.
 *
 * P1 scope: minimum services needed to keep current FileEditorPanel functional
 * (model + theme + textmate + languages + config). Command Palette / LSP /
 * Terminal services are deliberately deferred to later phases.
 */
import '@codingame/monaco-vscode-theme-defaults-default-extension';
import '@codingame/monaco-vscode-typescript-basics-default-extension';
import '@codingame/monaco-vscode-javascript-default-extension';
import '@codingame/monaco-vscode-json-default-extension';
import '@codingame/monaco-vscode-markdown-basics-default-extension';
import '@codingame/monaco-vscode-java-default-extension';
import '@codingame/monaco-vscode-xml-default-extension';
import '@codingame/monaco-vscode-html-default-extension';
import '@codingame/monaco-vscode-css-default-extension';
import '@codingame/monaco-vscode-yaml-default-extension';

import { initialize } from '@codingame/monaco-vscode-api';
import getModelServiceOverride from '@codingame/monaco-vscode-model-service-override';
import getConfigurationServiceOverride from '@codingame/monaco-vscode-configuration-service-override';
import getThemeServiceOverride from '@codingame/monaco-vscode-theme-service-override';
import getTextmateServiceOverride from '@codingame/monaco-vscode-textmate-service-override';
import getLanguagesServiceOverride from '@codingame/monaco-vscode-languages-service-override';

// Vite-native worker loader: `new Worker(new URL(..., import.meta.url))` is
// the canonical pattern. The `?worker` suffix would also work but URLs are
// more portable across bundlers.
window.MonacoEnvironment = {
    getWorker(_workerId: string, label: string) {
        switch (label) {
            case 'editorWorkerService':
            case 'TextEditorWorker':
                return new Worker(
                    new URL('monaco-editor/esm/vs/editor/editor.worker.js', import.meta.url),
                    { type: 'module' },
                );
            case 'TextMateWorker':
                return new Worker(
                    new URL(
                        '@codingame/monaco-vscode-textmate-service-override/worker',
                        import.meta.url,
                    ),
                    { type: 'module' },
                );
            default:
                // Worker labels occasionally change between major versions of monaco-vscode-api.
                // Log loudly so missing syntax highlighting / completions don't look like a silent
                // failure — the label name we received tells you which case to add.
                console.warn(
                    `[monaco] Unknown worker label "${label}". ` +
                    `Syntax highlighting / language features may be degraded. ` +
                    `Check the codingame/monaco-vscode-api wiki for the current worker label list.`,
                );
                throw new Error(`Unknown monaco worker label: ${label}`);
        }
    },
};

let initPromise: Promise<void> | null = null;

export function initMonaco(): Promise<void> {
    if (initPromise) return initPromise;
    initPromise = initialize({
        ...getModelServiceOverride(),
        ...getConfigurationServiceOverride(),
        ...getThemeServiceOverride(),
        ...getTextmateServiceOverride(),
        ...getLanguagesServiceOverride(),
    });
    return initPromise;
}
