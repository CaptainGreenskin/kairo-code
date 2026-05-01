package io.kairo.code.core.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.memory.MemoryStore;
import io.kairo.core.memory.FileMemoryStore;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMemoryStoreTest {

    @TempDir
    java.nio.file.Path tmp;

    @Test
    void saveAndRetrieve() {
        MemoryStore store = new FileMemoryStore(tmp);
        MemoryEntry entry = MemoryEntry.agent("e1", "agent-1", "remember this fact", Set.of());
        store.save(entry).block();

        MemoryEntry found = store.get("e1").block();
        assertThat(found).isNotNull();
        assertThat(found.content()).isEqualTo("remember this fact");
    }

    @Test
    void searchByBM25ReturnsRelevant() {
        MemoryStore store = new FileMemoryStore(tmp);

        MemoryEntry e1 = MemoryEntry.agent("e1", "agent-1",
            "spring boot java application with dependency injection", Set.of("java"));
        MemoryEntry e2 = MemoryEntry.agent("e2", "agent-1",
            "python machine learning neural network", Set.of("python"));

        store.save(e1).block();
        store.save(e2).block();

        // Use BM25MemorySearcher to search
        BM25MemorySearcher searcher = new BM25MemorySearcher(store);
        List<MemoryEntry> results = searcher.search("agent-1", "java spring boot", 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).content()).contains("spring boot java");
    }

    @Test
    void bm25SearchWithEmptyQueryReturnsImportanceRanked() {
        MemoryStore store = new FileMemoryStore(tmp);
        Instant now = Instant.now();

        MemoryEntry high = new MemoryEntry("h", "agent-1", "high importance", null,
            MemoryScope.AGENT, 0.9, null, Set.of(), now, null);
        MemoryEntry low = new MemoryEntry("l", "agent-1", "low importance", null,
            MemoryScope.AGENT, 0.1, null, Set.of(), now, null);

        store.save(high).block();
        store.save(low).block();

        BM25MemorySearcher searcher = new BM25MemorySearcher(store);
        List<MemoryEntry> results = searcher.search("agent-1", "", 5);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).importance()).isGreaterThan(results.get(1).importance());
    }

    @Test
    void bm25SearchWithNullQueryReturnsImportanceRanked() {
        MemoryStore store = new FileMemoryStore(tmp);
        Instant now = Instant.now();

        MemoryEntry high = new MemoryEntry("h", "agent-1", "high importance", null,
            MemoryScope.AGENT, 0.9, null, Set.of(), now, null);
        MemoryEntry low = new MemoryEntry("l", "agent-1", "low importance", null,
            MemoryScope.AGENT, 0.1, null, Set.of(), now, null);

        store.save(high).block();
        store.save(low).block();

        BM25MemorySearcher searcher = new BM25MemorySearcher(store);
        List<MemoryEntry> results = searcher.search("agent-1", null, 5);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).importance()).isGreaterThan(results.get(1).importance());
    }

    @Test
    void bm25SearchFiltersByAgentId() {
        MemoryStore store = new FileMemoryStore(tmp);

        store.save(MemoryEntry.agent("e1", "agent-1", "java spring boot", Set.of())).block();
        store.save(MemoryEntry.agent("e2", "agent-2", "java spring boot", Set.of())).block();

        BM25MemorySearcher searcher = new BM25MemorySearcher(store);
        List<MemoryEntry> results = searcher.search("agent-1", "java", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).agentId()).isEqualTo("agent-1");
    }

    @Test
    void bm25SearchIncludesGlobalMemories() {
        MemoryStore store = new FileMemoryStore(tmp);

        store.save(MemoryEntry.agent("e1", "agent-1", "agent specific memory", Set.of())).block();
        store.save(MemoryEntry.global("g1", "global java tip", 0.8)).block();

        BM25MemorySearcher searcher = new BM25MemorySearcher(store);
        List<MemoryEntry> results = searcher.search("agent-1", "java", 5);

        // Should include both agent and global memories that match
        assertThat(results).isNotEmpty();
        assertThat(results.stream().anyMatch(e -> e.scope() == MemoryScope.GLOBAL)).isTrue();
    }

    @Test
    void bm25SearchReturnsEmptyForNoMemories() {
        MemoryStore store = new FileMemoryStore(tmp);

        BM25MemorySearcher searcher = new BM25MemorySearcher(store);
        List<MemoryEntry> results = searcher.search("agent-1", "query", 5);

        assertThat(results).isEmpty();
    }

    @Test
    void bm25SearchRespectsTopNLimit() {
        MemoryStore store = new FileMemoryStore(tmp);

        for (int i = 0; i < 10; i++) {
            store.save(MemoryEntry.agent("e" + i, "agent-1",
                "java spring boot memory number " + i, Set.of())).block();
        }

        BM25MemorySearcher searcher = new BM25MemorySearcher(store);
        List<MemoryEntry> results = searcher.search("agent-1", "java", 3);

        assertThat(results).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void bm25RelevantDocScoresHigherThanIrrelevant() {
        MemoryStore store = new FileMemoryStore(tmp);

        MemoryEntry relevant = MemoryEntry.agent("e1", "agent-1",
            "spring boot java application with dependency injection", Set.of());
        MemoryEntry irrelevant = MemoryEntry.agent("e2", "agent-1",
            "python machine learning neural network", Set.of());

        store.save(relevant).block();
        store.save(irrelevant).block();

        BM25MemorySearcher searcher = new BM25MemorySearcher(store);
        List<MemoryEntry> results = searcher.search("agent-1", "java spring", 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0)).isEqualTo(relevant);
    }
}
