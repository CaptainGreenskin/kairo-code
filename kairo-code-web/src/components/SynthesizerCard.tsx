import { useState } from 'react';
import { LazyMarkdown } from './LazyMarkdown';
import type { CostSnapshot } from '../store/expertTeamStore';

export interface SynthesizerCardProps {
  finalOutput: string;
  cost: CostSnapshot;
  teamId: string;
  completedAt?: string;
  startedAt?: string;
  onReplay?: () => void;
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <button
      onClick={handleCopy}
      className="ml-2 px-2 py-0.5 text-[10px] font-medium rounded border
                 border-[var(--border)] text-[var(--text-secondary)]
                 hover:bg-[var(--bg-hover)] transition-colors"
    >
      {copied ? '✓ Copied' : 'Copy'}
    </button>
  );
}

function parseSection(content: string, heading: string): string | null {
  // Look for a markdown heading (## or ###) matching the section name
  const regex = new RegExp(
    `(?:^|\\n)#{2,3}\\s*${heading.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\n([\\s\\S]*?)(?=\\n#{2,3}\\s|$)`,
    'i',
  );
  const match = content.match(regex);
  return match ? match[1].trim() : null;
}

export function SynthesizerCard({
  finalOutput,
  cost,
  teamId,
  completedAt,
  startedAt,
  onReplay,
}: SynthesizerCardProps) {
  const sections = [
    { key: 'summary', title: 'Summary', icon: '📝' },
    { key: 'changes', title: 'Changes Made', icon: '🔧' },
    { key: 'tests', title: 'Test Results', icon: '🧪' },
    { key: 'pr', title: 'PR Description', icon: '📋', copyable: true },
    { key: 'risks', title: 'Remaining Risks', icon: '⚠️' },
  ];

  const sectionContents: Record<string, string | null> = {
    summary: parseSection(finalOutput, 'Summary'),
    changes: parseSection(finalOutput, 'Changes Made'),
    tests: parseSection(finalOutput, 'Test Results'),
    pr: parseSection(finalOutput, 'PR Description'),
    risks: parseSection(finalOutput, 'Remaining Risks'),
  };

  // Check if we found any structured sections
  const hasStructured = Object.values(sectionContents).some((v) => v != null);

  // Timing
  const elapsed = startedAt && completedAt
    ? Math.round((new Date(completedAt).getTime() - new Date(startedAt).getTime()) / 1000)
    : null;
  const elapsedStr = elapsed != null
    ? elapsed < 60
      ? `${elapsed}s`
      : `${Math.floor(elapsed / 60)}m ${elapsed % 60}s`
    : null;

  return (
    <div className="border border-[var(--border)] rounded-xl bg-[var(--bg-secondary)]
                    shadow-lg overflow-hidden">
      {/* Header */}
      <div className="px-5 py-4 border-b border-[var(--border)] bg-green-500/5">
        <div className="flex items-center gap-2">
          <span className="text-lg">✅</span>
          <h2 className="text-sm font-bold text-[var(--text-primary)]">
            Team Completed
          </h2>
          <span className="text-[10px] px-2 py-0.5 rounded-full bg-green-500/20 text-green-400 font-medium">
            Done
          </span>
        </div>
        <p className="text-[11px] text-[var(--text-muted)] mt-1">
          Team {teamId}
          {elapsedStr && ` · Completed in ${elapsedStr}`}
        </p>
        {onReplay && (
          <button
            onClick={onReplay}
            className="mt-2 px-3 py-1 text-[11px] font-medium rounded border
                       border-[var(--border)] text-[var(--text-secondary)]
                       hover:bg-[var(--bg-hover)] transition-colors"
          >
            ▶ Replay
          </button>
        )}
      </div>

      {/* Body */}
      <div className="p-5 space-y-4">
        {hasStructured ? (
          // Render structured sections
          sections.map(({ key, title, icon, copyable }) => {
            const content = sectionContents[key];
            if (!content) return null;
            return (
              <div key={key} className="border border-[var(--border)] rounded-lg overflow-hidden">
                <div className="flex items-center gap-1.5 px-3 py-2 bg-[var(--bg-primary)]
                                border-b border-[var(--border)]">
                  <span className="text-xs">{icon}</span>
                  <span className="text-xs font-semibold text-[var(--text-primary)]">{title}</span>
                  {copyable && <CopyButton text={content} />}
                </div>
                <div className="px-3 py-2 prose prose-sm prose-invert max-w-none overflow-x-auto
                                text-xs text-[var(--text-secondary)]
                                [&_p]:my-1 [&_ul]:my-1 [&_li]:my-0.5 [&_code]:text-[11px]
                                [&_pre]:bg-[var(--bg-primary)] [&_pre]:rounded [&_pre]:p-2
                                [&_table]:w-full [&_table]:text-[11px] [&_table]:border-collapse
                                [&_th]:px-2 [&_th]:py-1 [&_th]:text-left [&_th]:border [&_th]:border-[var(--border)] [&_th]:bg-[var(--bg-primary)]
                                [&_td]:px-2 [&_td]:py-1 [&_td]:border [&_td]:border-[var(--border)]">
                  <LazyMarkdown>{content}</LazyMarkdown>
                </div>
              </div>
            );
          })
        ) : (
          // Render full output as markdown
          <div className="prose prose-sm prose-invert max-w-none overflow-x-auto
                          text-xs text-[var(--text-secondary)]
                          [&_p]:my-1 [&_ul]:my-1 [&_li]:my-0.5 [&_code]:text-[11px]
                          [&_pre]:bg-[var(--bg-primary)] [&_pre]:rounded [&_pre]:p-2
                          [&_h2]:text-sm [&_h2]:font-semibold [&_h2]:text-[var(--text-primary)]
                          [&_h3]:text-xs [&_h3]:font-semibold [&_h3]:text-[var(--text-primary)]
                          [&_table]:w-full [&_table]:text-[11px] [&_table]:border-collapse
                          [&_th]:px-2 [&_th]:py-1 [&_th]:text-left [&_th]:border [&_th]:border-[var(--border)] [&_th]:bg-[var(--bg-primary)]
                          [&_td]:px-2 [&_td]:py-1 [&_td]:border [&_td]:border-[var(--border)]
                          [&_table]:rounded [&_table]:overflow-hidden">
            <LazyMarkdown>{finalOutput}</LazyMarkdown>
          </div>
        )}

        {/* Cost breakdown */}
        {(cost.spent > 0 || cost.budget > 0) && (
          <div className="border border-[var(--border)] rounded-lg overflow-hidden">
            <div className="flex items-center gap-1.5 px-3 py-2 bg-[var(--bg-primary)]
                            border-b border-[var(--border)]">
              <span className="text-xs">💰</span>
              <span className="text-xs font-semibold text-[var(--text-primary)]">Cost Breakdown</span>
            </div>
            <div className="px-3 py-2">
              <div className="flex items-center justify-between text-xs">
                <span className="text-[var(--text-secondary)]">Total Spent</span>
                <span className="font-mono text-[var(--text-primary)]">${cost.spent.toFixed(4)}</span>
              </div>
              {cost.budget > 0 && (
                <>
                  <div className="flex items-center justify-between text-xs mt-1">
                    <span className="text-[var(--text-secondary)]">Budget</span>
                    <span className="font-mono text-[var(--text-primary)]">${cost.budget.toFixed(4)}</span>
                  </div>
                  <div className="mt-2 w-full h-1.5 rounded-full bg-[var(--bg-primary)] overflow-hidden">
                    <div
                      className={`h-full rounded-full transition-all ${
                        cost.spent / cost.budget > 0.8 ? 'bg-red-500' :
                        cost.spent / cost.budget > 0.5 ? 'bg-amber-500' : 'bg-green-500'
                      }`}
                      style={{ width: `${Math.min((cost.spent / cost.budget) * 100, 100)}%` }}
                    />
                  </div>
                  <p className="text-[10px] text-[var(--text-muted)] mt-1 text-right">
                    {((cost.spent / cost.budget) * 100).toFixed(1)}% of budget used
                  </p>
                </>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
