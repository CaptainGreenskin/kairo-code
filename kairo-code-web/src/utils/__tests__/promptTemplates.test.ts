import { describe, it, expect, beforeEach } from 'vitest';
import { getTemplates, saveTemplate, deleteTemplate, updateTemplate } from '../promptTemplates';

describe('promptTemplates', () => {
    beforeEach(() => localStorage.clear());

    it('returns empty array for fresh storage', () => {
        expect(getTemplates()).toHaveLength(0);
    });

    it('saves and retrieves a template', () => {
        const t = saveTemplate('My Template', 'Do X with Y');
        const all = getTemplates();
        expect(all).toHaveLength(1);
        expect(all[0].name).toBe('My Template');
        expect(all[0].content).toBe('Do X with Y');
        expect(all[0].id).toBe(t.id);
    });

    it('trims whitespace on save', () => {
        saveTemplate('  trimmed  ', '  content  ');
        const all = getTemplates();
        expect(all[0].name).toBe('trimmed');
        expect(all[0].content).toBe('content');
    });

    it('deletes a template by id', () => {
        const t = saveTemplate('T1', 'content1');
        saveTemplate('T2', 'content2');
        deleteTemplate(t.id);
        const all = getTemplates();
        expect(all).toHaveLength(1);
        expect(all[0].name).toBe('T2');
    });

    it('updates a template', () => {
        const t = saveTemplate('Old Name', 'Old content');
        updateTemplate(t.id, 'New Name', 'New content');
        const all = getTemplates();
        expect(all[0].name).toBe('New Name');
        expect(all[0].content).toBe('New content');
    });

    it('accumulates multiple templates', () => {
        saveTemplate('A', 'aaa');
        saveTemplate('B', 'bbb');
        saveTemplate('C', 'ccc');
        expect(getTemplates()).toHaveLength(3);
    });

    it('generates unique ids', () => {
        const t1 = saveTemplate('A', 'aaa');
        const t2 = saveTemplate('B', 'bbb');
        expect(t1.id).not.toBe(t2.id);
    });
});
