import type { Message } from '@/types/agent';

function formatToolCalls(message: Message): string {
    if (!message.toolCalls || message.toolCalls.length === 0) return '';
    const lines: string[] = ['\n<details><summary>Tool Calls</summary>\n'];
    for (const tc of message.toolCalls) {
        lines.push(`**${tc.toolName}** (${tc.status})`);
        if (tc.result) {
            lines.push('```');
            lines.push(tc.result.slice(0, 500) + (tc.result.length > 500 ? '\n...(truncated)' : ''));
            lines.push('```');
        }
        lines.push('');
    }
    lines.push('</details>\n');
    return lines.join('\n');
}

export function exportChatAsMarkdown(messages: Message[], sessionId: string | null): void {
    const date = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    const lines: string[] = [
        `# kairo-code Chat Export`,
        ``,
        `**Session:** ${sessionId ?? 'unknown'}`,
        `**Date:** ${new Date().toLocaleString()}`,
        `**Messages:** ${messages.length}`,
        ``,
        `---`,
        ``,
    ];

    for (const msg of messages) {
        if (msg.role === 'user') {
            lines.push(`## User`);
            lines.push(``);
            lines.push(msg.content);
            lines.push(``);
        } else {
            lines.push(`## Assistant`);
            lines.push(``);
            if (msg.content) lines.push(msg.content);
            lines.push(formatToolCalls(msg));
        }
        lines.push(`---`);
        lines.push(``);
    }

    const blob = new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `kairo-code-${date}.md`;
    a.click();
    URL.revokeObjectURL(url);
}
