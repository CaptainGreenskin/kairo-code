import { useState } from 'react';
import { ChevronDown, ChevronRight, FileText } from 'lucide-react';

interface DiffLine {
    type: 'add' | 'remove' | 'context' | 'hunk' | 'header';
    content: string;
    lineNum?: number;
}

interface DiffFile {
    filename: string;
    lines: DiffLine[];
}

function parseDiff(raw: string): DiffFile[] {
    const files: DiffFile[] = [];
    let current: DiffFile | null = null;
    let lineNum = 0;

    for (const line of raw.split('\n')) {
        if (line.startsWith('--- ') || line.startsWith('+++ ')) {
            if (line.startsWith('+++ ')) {
                const filename = line.replace('+++ b/', '').replace('+++ ', '');
                current = { filename, lines: [] };
                files.push(current);
            }
        } else if (line.startsWith('@@ ')) {
            const match = line.match(/@@ -\d+(?:,\d+)? \+(\d+)/);
            lineNum = match ? parseInt(match[1]) - 1 : lineNum;
            current?.lines.push({ type: 'hunk', content: line });
        } else if (line.startsWith('+') && current) {
            lineNum++;
            current.lines.push({ type: 'add', content: line.slice(1), lineNum });
        } else if (line.startsWith('-') && current) {
            current.lines.push({ type: 'remove', content: line.slice(1) });
        } else if (current) {
            lineNum++;
            current.lines.push({ type: 'context', content: line.slice(1) || line, lineNum });
        }
    }
    return files;
}

interface DiffBlockProps {
    content: string;
}

export function DiffBlock({ content }: DiffBlockProps) {
    const files = parseDiff(content);
    const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});

    if (!files.length) {
        return <pre className="text-sm p-3 overflow-x-auto">{content}</pre>;
    }

    return (
        <div className="my-3 rounded-lg border border-[var(--border)] overflow-hidden">
            {files.map(file => {
                const isCollapsed = collapsed[file.filename];
                const addCount = file.lines.filter(l => l.type === 'add').length;
                const removeCount = file.lines.filter(l => l.type === 'remove').length;

                return (
                    <div key={file.filename}>
                        <div
                            className="flex items-center gap-2 px-3 py-2 bg-[var(--bg-secondary)] border-b border-[var(--border)] cursor-pointer select-none"
                            onClick={() => setCollapsed(c => ({ ...c, [file.filename]: !c[file.filename] }))}
                        >
                            {isCollapsed ? <ChevronRight size={14} /> : <ChevronDown size={14} />}
                            <FileText size={14} className="text-[var(--text-muted)]" />
                            <span className="text-xs font-mono text-[var(--text-primary)] flex-1 truncate">{file.filename}</span>
                            <span className="text-xs text-green-500 font-mono">+{addCount}</span>
                            <span className="text-xs text-red-400 font-mono ml-1">-{removeCount}</span>
                        </div>

                        {!isCollapsed && (
                            <div className="overflow-x-auto">
                                <table className="w-full text-xs font-mono border-collapse">
                                    <tbody>
                                        {file.lines.map((line, i) => {
                                            if (line.type === 'hunk') {
                                                return (
                                                    <tr key={i} className="bg-blue-950/30">
                                                        <td className="w-10 px-2 py-0.5 text-[var(--text-muted)] select-none text-right border-r border-[var(--border)]"></td>
                                                        <td className="px-3 py-0.5 text-blue-400">{line.content}</td>
                                                    </tr>
                                                );
                                            }
                                            const bg =
                                                line.type === 'add' ? 'bg-green-950/40' :
                                                line.type === 'remove' ? 'bg-red-950/40' :
                                                '';
                                            const prefix =
                                                line.type === 'add' ? '+' :
                                                line.type === 'remove' ? '-' :
                                                ' ';
                                            const textColor =
                                                line.type === 'add' ? 'text-green-300' :
                                                line.type === 'remove' ? 'text-red-300' :
                                                'text-[var(--text-secondary)]';

                                            return (
                                                <tr key={i} className={bg}>
                                                    <td className="w-10 px-2 py-0.5 text-[var(--text-muted)] select-none text-right border-r border-[var(--border)]">
                                                        {line.lineNum ?? ''}
                                                    </td>
                                                    <td className={`px-3 py-0.5 whitespace-pre ${textColor}`}>
                                                        <span className="select-none opacity-60 mr-1">{prefix}</span>
                                                        {line.content}
                                                    </td>
                                                </tr>
                                            );
                                        })}
                                    </tbody>
                                </table>
                            </div>
                        )}
                    </div>
                );
            })}
        </div>
    );
}
