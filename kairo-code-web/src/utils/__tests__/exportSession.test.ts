import { describe, it, expect } from 'vitest';
import { exportSessionAsMarkdown, exportSessionAsJson } from '../exportSession';
import type { Message } from '../../types/agent';

function makeMsg(role: Message['role'], content: string, id = role + '-1'): Message {
    return { id, role, content, toolCalls: [], timestamp: Date.now() } as unknown as Message;
}

describe('exportSessionAsMarkdown', () => {
    it('includes title and date header', () => {
        const md = exportSessionAsMarkdown([], 'My Session');
        expect(md).toContain('# My Session');
        expect(md).toContain('*Exported:');
    });

    it('uses default title when sessionName is empty', () => {
        const md = exportSessionAsMarkdown([], '');
        expect(md).toContain('# Session Export');
    });

    it('renders user messages under ## You', () => {
        const md = exportSessionAsMarkdown([makeMsg('user', 'Hello')], 'Test');
        expect(md).toContain('## You');
        expect(md).toContain('Hello');
    });

    it('renders assistant messages under ## Agent', () => {
        const md = exportSessionAsMarkdown([makeMsg('assistant', 'Hi there')], 'Test');
        expect(md).toContain('## Agent');
        expect(md).toContain('Hi there');
    });

    it('skips error messages', () => {
        const md = exportSessionAsMarkdown([makeMsg('error', 'fail')], 'Test');
        expect(md).not.toContain('fail');
    });

    it('handles null content gracefully', () => {
        const msg = { ...makeMsg('user', ''), content: null } as unknown as Message;
        expect(() => exportSessionAsMarkdown([msg], 'Test')).not.toThrow();
    });
});

describe('exportSessionAsJson', () => {
    it('produces valid JSON', () => {
        const json = exportSessionAsJson([], 'Test');
        expect(() => JSON.parse(json)).not.toThrow();
    });

    it('includes session name and messageCount', () => {
        const msgs = [makeMsg('user', 'q'), makeMsg('assistant', 'a')];
        const parsed = JSON.parse(exportSessionAsJson(msgs, 'MySession'));
        expect(parsed.session).toBe('MySession');
        expect(parsed.messageCount).toBe(2);
    });

    it('preserves role and content in messages array', () => {
        const msgs = [makeMsg('user', 'hello')];
        const parsed = JSON.parse(exportSessionAsJson(msgs, 'S'));
        expect(parsed.messages[0].role).toBe('user');
        expect(parsed.messages[0].content).toBe('hello');
    });

    it('uses default session name when empty', () => {
        const parsed = JSON.parse(exportSessionAsJson([], ''));
        expect(parsed.session).toBe('export');
    });
});
