import { useEffect, useRef, useState } from 'react';
import { List, X } from 'lucide-react';

interface Heading {
    id: string;
    text: string;
    level: number;
}

interface ReportLayoutProps {
    /** Number of H2/H3 headings → triggers TOC mode upstream; we just render. */
    headings: Heading[];
    children: React.ReactNode;
}

/**
 * Long-report renderer with a floating TOC. The body container holds the markdown
 * children unchanged; we only inject a sidebar that scroll-spies the active section.
 *
 * The TOC overlays the bubble (right-anchored, collapsible) instead of stealing width,
 * so it works inside the chat message and on narrow screens.
 */
export function ReportLayout({ headings, children }: ReportLayoutProps) {
    const [open, setOpen] = useState(true);
    const [activeId, setActiveId] = useState<string | null>(headings[0]?.id ?? null);
    const containerRef = useRef<HTMLDivElement | null>(null);

    useEffect(() => {
        const container = containerRef.current;
        if (!container) return;
        const els = headings
            .map(h => container.querySelector<HTMLElement>(`[data-heading-id="${h.id}"]`))
            .filter((x): x is HTMLElement => !!x);
        if (els.length === 0) return;

        const observer = new IntersectionObserver(
            (entries) => {
                const visible = entries
                    .filter(e => e.isIntersecting)
                    .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
                if (visible[0]) {
                    setActiveId(visible[0].target.getAttribute('data-heading-id'));
                }
            },
            { rootMargin: '-10% 0px -70% 0px', threshold: [0, 1] },
        );
        els.forEach(el => observer.observe(el));
        return () => observer.disconnect();
    }, [headings]);

    const handleJump = (id: string) => {
        const el = containerRef.current?.querySelector<HTMLElement>(`[data-heading-id="${id}"]`);
        if (el) {
            el.scrollIntoView({ behavior: 'smooth', block: 'start' });
            setActiveId(id);
        }
    };

    return (
        <div ref={containerRef} className="relative">
            {/* TOC toggle (collapsed pill) */}
            {!open && (
                <button
                    onClick={() => setOpen(true)}
                    title="Show outline"
                    className="absolute right-0 top-0 z-10 flex items-center gap-1 px-2 py-1 rounded-md text-[10px]
                        bg-[var(--bg-primary)] border border-[var(--border)] text-[var(--text-muted)]
                        hover:text-[var(--text-primary)] hover:border-[var(--accent)]/50 transition-colors"
                >
                    <List size={11} />
                    Outline ({headings.length})
                </button>
            )}

            {/* TOC sidebar (expanded) */}
            {open && (
                <aside
                    className="absolute right-0 top-0 z-10 w-48 max-h-[60vh] overflow-y-auto
                        rounded-md border border-[var(--border)] bg-[var(--bg-primary)] shadow-lg
                        text-xs"
                >
                    <div className="flex items-center justify-between px-3 py-2 border-b border-[var(--border)] sticky top-0 bg-[var(--bg-primary)]">
                        <span className="flex items-center gap-1.5 text-[10px] uppercase tracking-wider text-[var(--text-muted)]">
                            <List size={11} />
                            Outline
                        </span>
                        <button
                            onClick={() => setOpen(false)}
                            className="p-0.5 rounded hover:bg-[var(--bg-secondary)] text-[var(--text-muted)] hover:text-[var(--text-primary)]"
                        >
                            <X size={12} />
                        </button>
                    </div>
                    <ul className="py-1">
                        {headings.map(h => (
                            <li key={h.id}>
                                <button
                                    onClick={() => handleJump(h.id)}
                                    className={`w-full text-left px-3 py-1 truncate transition-colors hover:bg-[var(--bg-secondary)]
                                        ${activeId === h.id
                                            ? 'text-[var(--accent)] border-l-2 border-[var(--accent)] bg-[var(--bg-secondary)]'
                                            : 'text-[var(--text-secondary)] border-l-2 border-transparent'}`}
                                    style={{ paddingLeft: `${0.75 + (h.level - 2) * 0.75}rem` }}
                                    title={h.text}
                                >
                                    {h.text}
                                </button>
                            </li>
                        ))}
                    </ul>
                </aside>
            )}

            {/* Body — leave right padding so TOC doesn't clip text */}
            <div className={open ? 'pr-52' : 'pr-0'}>{children}</div>
        </div>
    );
}

/** Collect H2/H3 (and H4 if few headings) headings from markdown source. */
export function extractHeadings(source: string): Heading[] {
    const lines = source.split('\n');
    const result: Heading[] = [];
    let inFence = false;
    const seen = new Map<string, number>();
    for (const line of lines) {
        if (/^```/.test(line)) {
            inFence = !inFence;
            continue;
        }
        if (inFence) continue;
        const m = /^(#{2,4})\s+(.+?)\s*#*$/.exec(line);
        if (!m) continue;
        const level = m[1].length;
        const text = m[2].replace(/[*_`]/g, '').trim();
        if (!text) continue;
        const slug = text.toLowerCase()
            .replace(/[^\w\u4e00-\u9fa5\s-]/g, '')
            .replace(/\s+/g, '-')
            .slice(0, 60) || `h-${result.length}`;
        const count = seen.get(slug) ?? 0;
        seen.set(slug, count + 1);
        const id = count === 0 ? slug : `${slug}-${count}`;
        result.push({ id, text, level });
    }
    return result;
}

/**
 * Decide if a message warrants the report layout: enough headings AND enough content.
 * Tuned conservatively so chat doesn't pop a TOC for casual replies.
 */
export function shouldUseReportLayout(content: string, headings: Heading[]): boolean {
    if (content.length < 1500) return false;
    const h2h3 = headings.filter(h => h.level <= 3).length;
    return h2h3 >= 3;
}
