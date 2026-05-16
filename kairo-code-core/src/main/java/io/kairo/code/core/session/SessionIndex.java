package io.kairo.code.core.session;

import java.util.List;

/**
 * Index for searching session turns by keyword.
 */
public interface SessionIndex {

    /**
     * Add a turn to the index.
     *
     * @param turnIndex the 0-based position of the turn in the session
     * @param turn      the session turn to index
     */
    void index(int turnIndex, SessionTurn turn);

    /**
     * Search for turns matching the given query.
     *
     * @param query the search query (case-insensitive, whitespace-separated keywords)
     * @param limit maximum number of results to return
     * @return matching turns ordered by relevance (best match first)
     */
    List<SearchResult> search(String query, int limit);

    /**
     * A search result pairing a matched turn with its position and relevance score.
     *
     * @param turnIndex the 0-based position in the session
     * @param turn      the matched turn
     * @param score     relevance score (higher = better match)
     */
    record SearchResult(int turnIndex, SessionTurn turn, double score) {}
}
