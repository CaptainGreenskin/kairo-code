package io.kairo.code.core.prompt;

import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.memory.MemoryStore;
import io.kairo.code.core.memory.BM25MemorySearcher;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Queries a {@link MemoryStore} for relevant memories and formats them into a system prompt
 * section for session enrichment.
 *
 * <p>Memories are ranked by a weighted score combining importance and recency, then the top N
 * are rendered as a bullet list injected into the agent's system prompt.
 */
public final class SessionMemoryEnricher {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryEnricher.class);

    /** Default agent ID used for memory queries in kairo-code. */
    static final String AGENT_ID = "kairo-code";

    /** Maximum number of memories to include in the system prompt. */
    static final int MAX_MEMORIES = 10;

    /** Timeout for blocking memory queries during prompt building. */
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Half-life for recency decay in days. Memories older than this lose half their recency score.
     */
    private static final double RECENCY_HALF_LIFE_DAYS = 7.0;

    /** Weight of importance vs recency in the combined score (importance weight). */
    private static final double IMPORTANCE_WEIGHT = 0.6;

    /** Weight of recency in the combined score. */
    private static final double RECENCY_WEIGHT = 0.4;

    private SessionMemoryEnricher() {}

    /**
     * Query the memory store and format relevant memories into a prompt section.
     *
     * @param store the memory store to query (nullable — returns empty string if null)
     * @return the formatted memory section, or empty string if no memories exist
     */
    public static String buildMemorySection(MemoryStore store) {
        return buildMemorySection(store, null);
    }

    /**
     * Query the memory store with an optional user query for BM25-based ranking.
     *
     * @param store the memory store to query (nullable — returns empty string if null)
     * @param userQuery optional user message to use as BM25 search query; if null or blank,
     *                  falls back to importance+recency ranking
     * @return the formatted memory section, or empty string if no memories exist
     */
    public static String buildMemorySection(MemoryStore store, String userQuery) {
        if (store == null) {
            return "";
        }
        try {
            List<MemoryEntry> relevant;

            // Use BM25 search when a query is provided
            if (userQuery != null && !userQuery.isBlank()) {
                BM25MemorySearcher searcher = new BM25MemorySearcher(store);
                relevant = searcher.search(AGENT_ID, userQuery, MAX_MEMORIES);
            } else {
                // Fall back to importance+recency ranking
                List<MemoryEntry> agentMemories =
                        store.list(MemoryScope.AGENT).collectList().block(QUERY_TIMEOUT);
                List<MemoryEntry> globalMemories =
                        store.list(MemoryScope.GLOBAL).collectList().block(QUERY_TIMEOUT);

                List<MemoryEntry> allMemories = new java.util.ArrayList<>();
                if (agentMemories != null) allMemories.addAll(agentMemories);
                if (globalMemories != null) allMemories.addAll(globalMemories);

                if (allMemories.isEmpty()) {
                    return "";
                }
                relevant = rankMemories(allMemories);
            }

            if (relevant.isEmpty()) {
                return "";
            }

            return formatMemories(relevant);
        } catch (Exception e) {
            log.warn("Failed to query memory store for session enrichment: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Rank memories by combined importance + recency score, returning the top N.
     *
     * @param memories all candidate memories
     * @return ranked and truncated list
     */
    static List<MemoryEntry> rankMemories(List<MemoryEntry> memories) {
        Instant now = Instant.now();
        return memories.stream()
                .sorted(
                        Comparator.comparingDouble(
                                        (MemoryEntry e) -> computeScore(e, now))
                                .reversed())
                .limit(MAX_MEMORIES)
                .toList();
    }

    /**
     * Compute a combined score for a memory entry. Higher is better.
     *
     * @param entry the memory entry
     * @param now the current time for recency calculation
     * @return a score in [0.0, 1.0]
     */
    static double computeScore(MemoryEntry entry, Instant now) {
        double importance = entry.importance();
        double recency = computeRecency(entry.timestamp(), now);
        return IMPORTANCE_WEIGHT * importance + RECENCY_WEIGHT * recency;
    }

    /**
     * Compute a recency score using exponential decay.
     *
     * @param timestamp the memory timestamp (nullable — returns 0.0 if null)
     * @param now the current time
     * @return a score in [0.0, 1.0] where 1.0 is "just created"
     */
    static double computeRecency(Instant timestamp, Instant now) {
        if (timestamp == null || now == null) {
            return 0.0;
        }
        long ageMillis = Duration.between(timestamp, now).toMillis();
        if (ageMillis <= 0) {
            return 1.0;
        }
        double ageDays = ageMillis / (1000.0 * 60 * 60 * 24);
        return Math.exp(-0.693 * ageDays / RECENCY_HALF_LIFE_DAYS);
    }

    /**
     * Format ranked memories into a system prompt section.
     *
     * @param ranked the ranked list of memories (already truncated to MAX_MEMORIES)
     * @return the formatted section string
     */
    static String formatMemories(List<MemoryEntry> ranked) {
        if (ranked == null || ranked.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Your Memories\n");
        sb.append(
                "The following are memories from previous sessions."
                        + " Use them to provide better, more personalized assistance.\n\n");
        for (MemoryEntry entry : ranked) {
            sb.append("- [importance: ")
                    .append(String.format(java.util.Locale.ROOT, "%.1f", entry.importance()))
                    .append("] ")
                    .append(entry.content())
                    .append("\n");
        }
        return sb.toString();
    }
}
