import { describe, it, expect } from 'vitest';
import { detectLanguage, formatFileBlock } from '../fileReader';

describe('fileReader', () => {
    describe('detectLanguage', () => {
        it('detects TypeScript', () => {
            expect(detectLanguage('foo.ts')).toBe('typescript');
        });

        it('detects Python', () => {
            expect(detectLanguage('script.py')).toBe('python');
        });

        it('detects YAML', () => {
            expect(detectLanguage('config.yml')).toBe('yaml');
            expect(detectLanguage('config.yaml')).toBe('yaml');
        });

        it('handles Dockerfile (no extension)', () => {
            expect(detectLanguage('Dockerfile')).toBe('dockerfile');
        });

        it('returns text for unknown extension', () => {
            expect(detectLanguage('file.xyz')).toBe('text');
        });

        it('is case-insensitive', () => {
            expect(detectLanguage('File.TS')).toBe('typescript');
        });
    });

    describe('formatFileBlock', () => {
        it('wraps content in code block', () => {
            const result = formatFileBlock({ name: 'foo.ts', language: 'typescript', content: 'const x = 1;', truncated: false });
            expect(result).toContain('```typescript');
            expect(result).toContain('// foo.ts');
            expect(result).toContain('const x = 1;');
        });

        it('adds truncated marker when truncated', () => {
            const result = formatFileBlock({ name: 'big.log', language: 'text', content: '...', truncated: true });
            expect(result).toContain('(truncated)');
        });
    });
});
