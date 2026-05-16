package io.kairo.code.core.session;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * In-memory inverted index for session turn search.
 *
 * <p>Tokenizes turn content into lowercase words, builds a term→turnIndex posting list,
 * and scores matches using TF (term frequency within the turn).
 *
 * <p>Thread-safe for concurrent index/search via synchronized methods.
 */
public class InMemorySessionIndex implements SessionIndex {

    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[\\s\\p{Punct}]+");

    private final Map<String, Set<Integer>> invertedIndex = new HashMap<>();
    private final Map<Integer, SessionTurn> turnStore = new HashMap<>();
    private final Map<Integer, Map<String, Integer>> termFrequencies = new HashMap<>();

    @Override
    public synchronized void index(int turnIndex, SessionTurn turn) {
        if (turn == null || turn.content() == null || turn.content().isBlank()) {
            return;
        }
        turnStore.put(turnIndex, turn);

        Map<String, Integer> tf = new HashMap<>();
        for (String token : tokenize(turn.content())) {
            invertedIndex.computeIfAbsent(token, k -> new HashSet<>()).add(turnIndex);
            tf.merge(token, 1, Integer::sum);
        }
        termFrequencies.put(turnIndex, tf);
    }

    @Override
    public synchronized List<SearchResult> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }

        String[] queryTerms = tokenize(query);
        if (queryTerms.length == 0) {
            return List.of();
        }

        Map<Integer, Double> scores = new HashMap<>();

        for (String term : queryTerms) {
            Set<Integer> postings = invertedIndex.get(term);
            if (postings == null) {
                continue;
            }
            double idf = Math.log((double) (turnStore.size() + 1) / (postings.size() + 1)) + 1.0;
            for (int idx : postings) {
                Map<String, Integer> tf = termFrequencies.get(idx);
                int freq = tf != null ? tf.getOrDefault(term, 0) : 0;
                scores.merge(idx, freq * idf, Double::sum);
            }
        }

        List<SearchResult> results = new ArrayList<>();
        for (var entry : scores.entrySet()) {
            SessionTurn turn = turnStore.get(entry.getKey());
            if (turn != null) {
                results.add(new SearchResult(entry.getKey(), turn, entry.getValue()));
            }
        }

        results.sort(Comparator.comparingDouble(SearchResult::score).reversed());

        if (results.size() > limit) {
            return results.subList(0, limit);
        }
        return results;
    }

    private static String[] tokenize(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        String[] tokens = TOKEN_SPLITTER.split(lower);
        List<String> filtered = new ArrayList<>();
        for (String t : tokens) {
            if (!t.isEmpty() && t.length() >= 2) {
                filtered.add(t);
            }
        }
        return filtered.toArray(String[]::new);
    }
}
