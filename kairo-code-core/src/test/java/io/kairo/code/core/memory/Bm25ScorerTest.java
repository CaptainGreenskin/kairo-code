package io.kairo.code.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class Bm25ScorerTest {

    @Test
    void tokenizeAscii() {
        List<String> tokens = Bm25Scorer.tokenize("Hello World foo-bar");
        assertTrue(tokens.contains("hello"));
        assertTrue(tokens.contains("world"));
    }

    @Test
    void tokenizeCJKBigrams() {
        List<String> tokens = Bm25Scorer.tokenize("你好世界");
        assertTrue(tokens.contains("你好"));
        assertTrue(tokens.contains("好世"));
        assertTrue(tokens.contains("世界"));
    }

    @Test
    void emptyQueryReturnsZero() {
        double score = Bm25Scorer.score(List.of(), "some document content", null, 10.0);
        assertEquals(0.0, score);
    }

    @Test
    void nullQueryReturnsZero() {
        double score = Bm25Scorer.score(null, "some document content", null, 10.0);
        assertEquals(0.0, score);
    }

    @Test
    void relevantDocScoresHigher() {
        List<String> query = Bm25Scorer.tokenize("java spring boot");
        double relevant = Bm25Scorer.score(query,
            "spring boot java application with dependency injection", null, 10.0);
        double irrelevant = Bm25Scorer.score(query,
            "python machine learning neural network", null, 10.0);
        assertTrue(relevant > irrelevant);
    }

    @Test
    void titleBoostIncreasesScore() {
        List<String> query = Bm25Scorer.tokenize("spring");
        double withTitle = Bm25Scorer.score(query, "some content", "spring boot guide", 10.0);
        double withoutTitle = Bm25Scorer.score(query, "some content", null, 10.0);
        assertTrue(withTitle > withoutTitle);
    }

    @Test
    void tokenizeNullReturnsEmpty() {
        assertTrue(Bm25Scorer.tokenize(null).isEmpty());
    }

    @Test
    void tokenizeBlankReturnsEmpty() {
        assertTrue(Bm25Scorer.tokenize("   ").isEmpty());
    }

    @Test
    void scoreWithNullContentReturnsZero() {
        List<String> query = Bm25Scorer.tokenize("test");
        double score = Bm25Scorer.score(query, null, null, 10.0);
        assertEquals(0.0, score);
    }
}
