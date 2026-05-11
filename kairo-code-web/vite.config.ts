import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import pkg from './package.json';
import importMetaUrlPlugin from '@codingame/esbuild-import-meta-url-plugin';

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, process.cwd(), '');

    return {
        plugins: [react()],

        define: {
            global: 'globalThis',
            __APP_VERSION__: JSON.stringify(pkg.version),
        },

        resolve: {
            alias: {
                '@': path.resolve(__dirname, './src'),
                '@components': path.resolve(__dirname, './src/components'),
                '@store': path.resolve(__dirname, './src/store'),
                '@api': path.resolve(__dirname, './src/api'),
                '@hooks': path.resolve(__dirname, './src/hooks'),
                '@types': path.resolve(__dirname, './src/types'),
                '@utils': path.resolve(__dirname, './src/utils'),
                // monaco-vscode-api wildcard exports (./vscode/* → ./vscode/src/*) are not
                // fully resolved by Rollup in production builds. This alias bridges the gap.
                '@codingame/monaco-vscode-api/vscode/vs': path.resolve(__dirname, 'node_modules/@codingame/monaco-vscode-api/vscode/src/vs'),
            },
        },

        server: {
            port: 5173,
            host: true,
            proxy: {
                '/api': {
                    target: env.VITE_API_URL || 'http://localhost:8080',
                    changeOrigin: true,
                    secure: false,
                },
                '/ws': {
                    target: env.VITE_API_URL || 'http://localhost:8080',
                    changeOrigin: true,
                    secure: false,
                    ws: true,
                },
            },
        },

        optimizeDeps: {
            esbuildOptions: {
                plugins: [importMetaUrlPlugin],
            },
            include: [
                'monaco-editor',
                '@codingame/monaco-vscode-api',
                '@codingame/monaco-vscode-textmate-service-override',
                '@codingame/monaco-vscode-theme-service-override',
                '@codingame/monaco-vscode-languages-service-override',
                '@codingame/monaco-vscode-model-service-override',
                '@codingame/monaco-vscode-configuration-service-override',
            ],
        },

        worker: {
            format: 'es',
        },

        build: {
            outDir: 'dist',
            sourcemap: mode === 'development',
            rollupOptions: {
                output: {
                    manualChunks: {
                        'react-vendor': ['react', 'react-dom'],
                        'xterm': ['@xterm/xterm', '@xterm/addon-fit'],
                        'markdown': ['react-markdown'],
                        'syntax-highlighter': ['react-syntax-highlighter'],
                        'virtuoso': ['react-virtuoso'],
                        'monaco': ['monaco-editor', '@codingame/monaco-vscode-api'],
                    },
                },
            },
        },

        envPrefix: 'VITE_',

        test: {
            environment: 'jsdom',
            globals: true,
            setupFiles: ['src/test/setup.ts'],
        },
    };
});
