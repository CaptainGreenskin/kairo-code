/**
 * Returns a score >= 0 if `query` fuzzy-matches `text`, -1 if no match.
 * Higher score = better match. Consecutive character matches bonus.
 */
export function fuzzyMatch(text: string, query: string): number {
    if (!query) return 0;
    const t = text.toLowerCase();
    const q = query.toLowerCase();

    // Exact substring: highest priority
    if (t.includes(q)) return 1000 - t.indexOf(q);

    // Fuzzy: all query chars must appear in order
    let ti = 0;
    let qi = 0;
    let score = 0;
    let consecutive = 0;

    while (ti < t.length && qi < q.length) {
        if (t[ti] === q[qi]) {
            score += 10 + consecutive * 5;
            consecutive++;
            qi++;
        } else {
            consecutive = 0;
        }
        ti++;
    }

    return qi === q.length ? score : -1;
}
