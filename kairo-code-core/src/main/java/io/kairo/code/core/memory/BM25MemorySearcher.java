package io.kairo.code.core.memory;

import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.memory.MemoryStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * In-memory BM25-based memory searcher.
 *
 * <p>Fetches entries from a {@link MemoryStore}, then ranks them using BM25 relevance
 * blended with importance and recency scores.
 */
public final class BM25MemorySearcher {

    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(5);

    private final MemoryStore memoryStore;

    public BM25MemorySearcher(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    /**
     * Search memories by BM25 relevance score, blended with importance and recency.
     *
     * @param agentId  agent scope
     * @param query    free-text query
     * @param topN     maximum results to return
     * @return sorted list (highest relevance first)
     */
    public List<MemoryEntry> search(String agentId, String query, int topN) {
        // Fetch all memories for agent (AGENT scope + GLOBAL scope)
        List<MemoryEntry> all = fetchAllMemories(agentId);
        if (all == null || all.isEmpty()) return List.of();

        List<String> queryTokens = Bm25Scorer.tokenize(query);
        if (queryTokens.isEmpty()) {
            // No query — return top by importance+recency
            Instant now = Instant.now();
            return all.stream()
                .sorted(Comparator.comparingDouble((MemoryEntry e) ->
                    e.importance() * 0.6 + recencyScore(e, now) * 0.4).reversed())
                .limit(topN)
                .collect(Collectors.toList());
        }

        // Compute average doc length for BM25 normalization
        double avgLen = all.stream()
            .mapToInt(e -> Bm25Scorer.tokenize(e.content()).size())
            .average()
            .orElse(50.0);

        Instant now = Instant.now();
        return all.stream()
            .map(entry -> {
                double bm25 = Bm25Scorer.score(queryTokens,
                    entry.content(),
                    extractTitle(entry),
                    avgLen);
                // Blend: BM25 dominates when query provided
                double blended = bm25 * 0.7
                    + entry.importance() * 0.2
                    + recencyScore(entry, now) * 0.1;
                return Map.entry(entry, blended);
            })
            .filter(p -> p.getValue() > 0)
            .sorted(Map.Entry.<MemoryEntry, Double>comparingByValue().reversed())
            .limit(topN)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    private List<MemoryEntry> fetchAllMemories(String agentId) {
        try {
            List<MemoryEntry> agentMemories =
                memoryStore.list(MemoryScope.AGENT)
                    .filter(e -> agentId == null || agentId.equals(e.agentId()))
                    .collectList()
                    .block(QUERY_TIMEOUT);
            List<MemoryEntry> globalMemories =
                memoryStore.list(MemoryScope.GLOBAL)
                    .collectList()
                    .block(QUERY_TIMEOUT);

            List<MemoryEntry> all = new java.util.ArrayList<>();
            if (agentMemories != null) all.addAll(agentMemories);
            if (globalMemories != null) all.addAll(globalMemories);
            return all;
        } catch (Exception e) {
            return List.of();
        }
    }

    private double recencyScore(MemoryEntry entry, Instant now) {
        if (entry.timestamp() == null || now == null) {
            return 0.0;
        }
        long ageMs = Duration.between(entry.timestamp(), now).toMillis();
        long oneDayMs = 86_400_000L;
        return Math.max(0, 1.0 - (double) ageMs / (7 * oneDayMs)); // decay over 7 days
    }

    private String extractTitle(MemoryEntry entry) {
        // First line of content as title
        String content = entry.content();
        if (content == null) return null;
        int nl = content.indexOf('\n');
        return nl > 0 ? content.substring(0, nl) : content;
    }
}
