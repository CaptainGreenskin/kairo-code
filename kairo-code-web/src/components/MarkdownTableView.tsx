import type { MarkdownTable } from '@utils/markdownTable';

const ALIGN_CLASS: Record<string, string> = {
    left: 'text-left',
    center: 'text-center',
    right: 'text-right',
    none: 'text-left',
};

interface MarkdownTableViewProps {
    table: MarkdownTable;
}

export function MarkdownTableView({ table }: MarkdownTableViewProps) {
    const { headers, rows, alignments } = table;

    return (
        <div className="my-3 overflow-x-auto rounded-lg border border-[var(--border)]">
            <table className="w-full text-xs border-collapse">
                <thead>
                    <tr className="bg-[var(--bg-secondary)] border-b border-[var(--border)]">
                        {headers.map((h, i) => (
                            <th
                                key={i}
                                className={`px-3 py-2 font-medium text-[var(--text-primary)] whitespace-nowrap ${ALIGN_CLASS[alignments[i] ?? 'none']}`}
                            >
                                {h}
                            </th>
                        ))}
                    </tr>
                </thead>
                <tbody>
                    {rows.map((row, ri) => (
                        <tr
                            key={ri}
                            className={`border-b border-[var(--border)] last:border-0 ${
                                ri % 2 === 0 ? 'bg-[var(--bg-primary)]' : 'bg-[var(--bg-secondary)]/50'
                            } hover:bg-[var(--bg-secondary)] transition-colors`}
                        >
                            {row.map((cell, ci) => (
                                <td
                                    key={ci}
                                    className={`px-3 py-2 text-[var(--text-secondary)] ${ALIGN_CLASS[alignments[ci] ?? 'none']}`}
                                >
                                    {cell}
                                </td>
                            ))}
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}
