package io.kairo.code.core.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * BM25 relevance scorer with bigram tokenization for CJK support.
 *
 * <p>Parameters: k1=1.2, b=0.75, TITLE_BOOST=2.0.
 */
public final class Bm25Scorer {

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final double TITLE_BOOST = 2.0;

    private Bm25Scorer() {}

    /**
     * Tokenize: split on whitespace + punctuation, emit bigrams for CJK runs.
     */
    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        // ASCII words
        Matcher m = Pattern.compile("[\\w']+").matcher(text);
        while (m.find()) tokens.add(m.group().toLowerCase());
        // CJK bigrams
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (isCJK(chars[i]) && isCJK(chars[i + 1])) {
                tokens.add(new String(new char[]{chars[i], chars[i + 1]}));
            }
        }
        return tokens;
    }

    private static boolean isCJK(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF')
            || (c >= '\u3040' && c <= '\u30FF')
            || (c >= '\uAC00' && c <= '\uD7A3');
    }

    /**
     * Score a single document against a query.
     *
     * @param queryTokens   pre-tokenized query
     * @param docContent    document body text
     * @param docTitle      optional title (boosted by TITLE_BOOST); may be null
     * @param avgDocLen     average document length in the corpus
     */
    public static double score(List<String> queryTokens,
                                String docContent,
                                String docTitle,
                                double avgDocLen) {
        if (queryTokens == null || queryTokens.isEmpty()) {
            return 0.0;
        }

        List<String> docTokens = tokenize(docContent);
        int docLen = docTokens.size();
        Map<String, Long> tf = docTokens.stream()
            .collect(Collectors.groupingBy(t -> t, Collectors.counting()));

        double score = 0.0;
        for (String qt : queryTokens) {
            long freq = tf.getOrDefault(qt, 0L);
            if (freq > 0) {
                double idf = Math.log(1 + 1.0); // simplified IDF=1 for single doc scoring
                double tf_norm = (freq * (K1 + 1))
                    / (freq + K1 * (1 - B + B * docLen / Math.max(1, avgDocLen)));
                score += idf * tf_norm;
            }
        }

        // Title boost
        if (docTitle != null && !docTitle.isBlank()) {
            List<String> titleTokens = tokenize(docTitle);
            Map<String, Long> titleTf = titleTokens.stream()
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()));
            for (String qt : queryTokens) {
                long freq = titleTf.getOrDefault(qt, 0L);
                if (freq > 0) {
                    score += TITLE_BOOST * freq;
                }
            }
        }
        return score;
    }
}
