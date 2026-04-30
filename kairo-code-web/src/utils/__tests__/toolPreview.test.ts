import { describe, it, expect } from 'vitest';
import { extractFileWriteInfo, getToolRiskLevel } from '../toolPreview';

describe('extractFileWriteInfo', () => {
    it('returns null for non-write tools', () => {
        expect(extractFileWriteInfo('read_file', { path: 'a.ts', content: 'x' })).toBeNull();
        expect(extractFileWriteInfo('bash', { command: 'ls' })).toBeNull();
    });

    it('extracts path and content for write_file', () => {
        const info = extractFileWriteInfo('write_file', { path: 'src/main.ts', content: 'const x = 1;' });
        expect(info).not.toBeNull();
        expect(info!.filePath).toBe('src/main.ts');
        expect(info!.content).toBe('const x = 1;');
    });

    it('extracts from create_file using file_path key', () => {
        const info = extractFileWriteInfo('create_file', { file_path: 'foo.py', content: 'pass' });
        expect(info!.filePath).toBe('foo.py');
    });

    it('returns null when content is missing', () => {
        expect(extractFileWriteInfo('write_file', { path: 'a.ts' })).toBeNull();
    });

    it('returns null when path is missing', () => {
        expect(extractFileWriteInfo('write_file', { content: 'hello' })).toBeNull();
    });

    it('infers TypeScript language for .ts files', () => {
        const info = extractFileWriteInfo('write_file', { path: 'app.ts', content: 'x' });
        expect(info!.language).toBe('typescript');
    });

    it('infers Python language for .py files', () => {
        const info = extractFileWriteInfo('write_file', { path: 'main.py', content: 'x' });
        expect(info!.language).toBe('python');
    });

    it('falls back to text for unknown extensions', () => {
        const info = extractFileWriteInfo('write_file', { path: 'file.xyz', content: 'x' });
        expect(info!.language).toBe('text');
    });
});

describe('getToolRiskLevel', () => {
    it('classifies bash as execute', () => {
        expect(getToolRiskLevel('bash')).toBe('execute');
    });

    it('classifies write_file as write', () => {
        expect(getToolRiskLevel('write_file')).toBe('write');
    });

    it('classifies read_file as read', () => {
        expect(getToolRiskLevel('read_file')).toBe('read');
    });

    it('classifies unknown as other', () => {
        expect(getToolRiskLevel('some_custom_tool')).toBe('other');
    });
});
