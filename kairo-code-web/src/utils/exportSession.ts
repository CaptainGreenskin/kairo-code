import type { Message } from '../types/agent';

export interface ExportOptions {
    format: 'markdown' | 'json';
    sessionName?: string;
}

export function exportSessionAsMarkdown(messages: Message[], sessionName: string): string {
    const title = sessionName || 'Session Export';
    const date = new Date().toLocaleString();
    const lines: string[] = [
        `# ${title}`,
        ``,
        `*Exported: ${date}*`,
        ``,
        `---`,
        ``,
    ];

    for (const msg of messages) {
        if (msg.role === 'user') {
            lines.push(`## You`);
            lines.push(``);
            lines.push(msg.content ?? '');
            lines.push(``);
        } else if (msg.role === 'assistant') {
            lines.push(`## Agent`);
            lines.push(``);
            lines.push(msg.content ?? '');
            lines.push(``);
        }
        // error messages are skipped
    }

    return lines.join('\n');
}

export function exportSessionAsJson(messages: Message[], sessionName: string): string {
    return JSON.stringify({
        session: sessionName || 'export',
        exportedAt: new Date().toISOString(),
        messageCount: messages.length,
        messages: messages.map(m => ({
            role: m.role,
            content: m.content,
            timestamp: m.timestamp,
        })),
    }, null, 2);
}

export function downloadFile(content: string, filename: string, mimeType: string): void {
    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
}

export function exportAndDownload(messages: Message[], sessionName: string, format: 'markdown' | 'json'): void {
    const slug = (sessionName || 'session').replace(/[^a-z0-9]/gi, '-').toLowerCase().slice(0, 40);
    if (format === 'markdown') {
        const md = exportSessionAsMarkdown(messages, sessionName);
        downloadFile(md, `${slug}.md`, 'text/markdown');
    } else {
        const json = exportSessionAsJson(messages, sessionName);
        downloadFile(json, `${slug}.json`, 'application/json');
    }
}

export async function copySessionToClipboard(messages: Message[], sessionName: string): Promise<void> {
    const md = exportSessionAsMarkdown(messages, sessionName);
    await navigator.clipboard.writeText(md);
}
