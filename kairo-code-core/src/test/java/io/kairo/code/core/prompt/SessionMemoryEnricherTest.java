package io.kairo.code.core.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.memory.MemoryStore;
import io.kairo.core.memory.FileMemoryStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SessionMemoryEnricher}: memory ranking, formatting, and integration
 * with {@link FileMemoryStore}.
 */
class SessionMemoryEnricherTest {

    @TempDir
    Path tempDir;

    // --- buildMemorySection ---

    @Test
    void buildMemorySection_nullStore_returnsEmpty() {
        String result = SessionMemoryEnricher.buildMemorySection(null);
        assertThat(result).isEmpty();
    }

    @Test
    void buildMemorySection_emptyStore_returnsEmpty() {
        MemoryStore store = new FileMemoryStore(tempDir.resolve("empty-mem"));
        String result = SessionMemoryEnricher.buildMemorySection(store);
        assertThat(result).isEmpty();
    }

    @Test
    void buildMemorySection_withMemories_containsHeader() {
        MemoryStore store = new FileMemoryStore(tempDir.resolve("mem"));
        store.save(MemoryEntry.agent("m1", "kairo-code", "User prefers Kotlin", Set.of("lang")))
                .block();

        String result = SessionMemoryEnricher.buildMemorySection(store);
        assertThat(result).contains("## Your Memories");
        assertThat(result).contains("User prefers Kotlin");
        assertThat(result).contains("[importance: 0.5]");
    }

    @Test
    void buildMemorySection_includesGlobalMemories() {
        MemoryStore store = new FileMemoryStore(tempDir.resolve("mem2"));
        store.save(MemoryEntry.global("g1", "Always use UTF-8", 0.9)).block();

        String result = SessionMemoryEnricher.buildMemorySection(store);
        assertThat(result).contains("Always use UTF-8");
        assertThat(result).contains("[importance: 0.9]");
    }

    @Test
    void buildMemorySection_excludesSessionScopedMemories() {
        MemoryStore store = new FileMemoryStore(tempDir.resolve("mem3"));
        store.save(MemoryEntry.session("s1", "session-only data", Set.of())).block();

        String result = SessionMemoryEnricher.buildMemorySection(store);
        assertThat(result).isEmpty();
    }

    // --- ranking ---

    @Test
    void rankMemories_sortsHighImportanceFirst() {
        Instant now = Instant.now();
        MemoryEntry high = new MemoryEntry("h", null, "high", null,
                MemoryScope.AGENT, 0.9, null, Set.of(), now, null);
        MemoryEntry low = new MemoryEntry("l", null, "low", null,
                MemoryScope.AGENT, 0.1, null, Set.of(), now, null);

        List<MemoryEntry> ranked = SessionMemoryEnricher.rankMemories(List.of(low, high));
        assertThat(ranked).extracting(MemoryEntry::content).containsExactly("high", "low");
    }

    @Test
    void rankMemories_limitsToMax() {
        Instant now = Instant.now();
        java.util.List<MemoryEntry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            entries.add(new MemoryEntry("m" + i, null, "mem-" + i, null,
                    MemoryScope.AGENT, 0.5, null, Set.of(), now, null));
        }

        List<MemoryEntry> ranked = SessionMemoryEnricher.rankMemories(entries);
        assertThat(ranked).hasSize(SessionMemoryEnricher.MAX_MEMORIES);
    }

    @Test
    void rankMemories_recentMemoriesRankedHigher() {
        Instant now = Instant.now();
        MemoryEntry recent = new MemoryEntry("r", null, "recent", null,
                MemoryScope.AGENT, 0.5, null, Set.of(), now, null);
        MemoryEntry old = new MemoryEntry("o", null, "old", null,
                MemoryScope.AGENT, 0.5, null, Set.of(), now.minus(Duration.ofDays(30)), null);

        List<MemoryEntry> ranked = SessionMemoryEnricher.rankMemories(List.of(old, recent));
        assertThat(ranked).extracting(MemoryEntry::content).containsExactly("recent", "old");
    }

    // --- computeScore ---

    @Test
    void computeScore_recentHighImportance_maxScore() {
        Instant now = Instant.now();
        MemoryEntry entry = new MemoryEntry("x", null, "test", null,
                MemoryScope.AGENT, 1.0, null, Set.of(), now, null);
        double score = SessionMemoryEnricher.computeScore(entry, now);
        // importance_weight * 1.0 + recency_weight * 1.0 = 0.6 + 0.4 = 1.0
        assertThat(score).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void computeScore_oldLowImportance_lowScore() {
        Instant now = Instant.now();
        MemoryEntry entry = new MemoryEntry("x", null, "test", null,
                MemoryScope.AGENT, 0.1, null, Set.of(), now.minus(Duration.ofDays(60)), null);
        double score = SessionMemoryEnricher.computeScore(entry, now);
        // importance: 0.6 * 0.1 = 0.06; recency is near 0 for 60-day-old entry
        assertThat(score).isLessThan(0.1);
    }

    // --- computeRecency ---

    @Test
    void computeRecency_nullTimestamp_returnsZero() {
        assertThat(SessionMemoryEnricher.computeRecency(null, Instant.now())).isEqualTo(0.0);
    }

    @Test
    void computeRecency_justCreated_returnsOne() {
        Instant now = Instant.now();
        assertThat(SessionMemoryEnricher.computeRecency(now, now))
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void computeRecency_sevenDaysOld_returnsHalf() {
        Instant now = Instant.now();
        Instant sevenDaysAgo = now.minus(Duration.ofDays(7));
        double recency = SessionMemoryEnricher.computeRecency(sevenDaysAgo, now);
        // Half-life is 7 days, so recency should be ~0.5
        assertThat(recency).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.05));
    }

    // --- formatMemories ---

    @Test
    void formatMemories_emptyList_returnsEmpty() {
        assertThat(SessionMemoryEnricher.formatMemories(List.of())).isEmpty();
    }

    @Test
    void formatMemories_nullList_returnsEmpty() {
        assertThat(SessionMemoryEnricher.formatMemories(null)).isEmpty();
    }

    @Test
    void formatMemories_singleEntry_formatsCorrectly() {
        MemoryEntry entry = new MemoryEntry("x", null, "User likes dark mode", null,
                MemoryScope.AGENT, 0.8, null, Set.of(), Instant.now(), null);
        String result = SessionMemoryEnricher.formatMemories(List.of(entry));
        assertThat(result).contains("## Your Memories");
        assertThat(result).contains("- [importance: 0.8] User likes dark mode");
    }

    // --- FileMemoryStore integration ---

    @Test
    void fileMemoryStore_initializesWithCorrectPath() {
        Path memDir = tempDir.resolve("test-project/.kairo-code/memory");
        FileMemoryStore store = new FileMemoryStore(memDir);
        assertThat(store.getStorageDir()).isEqualTo(memDir);
    }

    @Test
    void fileMemoryStore_persistsAndReloads() {
        Path memDir = tempDir.resolve("persist-test");
        FileMemoryStore store1 = new FileMemoryStore(memDir);
        store1.save(MemoryEntry.agent("p1", "kairo-code",
                "Project uses PostgreSQL 15", Set.of("db"))).block();

        // Create a new store instance pointing to the same dir (simulates restart)
        FileMemoryStore store2 = new FileMemoryStore(memDir);
        String section = SessionMemoryEnricher.buildMemorySection(store2);
        assertThat(section).contains("Project uses PostgreSQL 15");
    }

    @Test
    void buildMemorySection_multipleMemories_sortedByScore() {
        MemoryStore store = new FileMemoryStore(tempDir.resolve("multi"));
        Instant now = Instant.now();

        // High importance + recent
        store.save(new MemoryEntry("m1", "kairo-code", "High priority recent", null,
                MemoryScope.AGENT, 0.9, null, Set.of(), now, null)).block();
        // Low importance + old
        store.save(new MemoryEntry("m2", "kairo-code", "Low priority old", null,
                MemoryScope.AGENT, 0.1, null, Set.of(), now.minus(Duration.ofDays(30)), null)).block();
        // Medium importance + recent
        store.save(new MemoryEntry("m3", "kairo-code", "Medium priority recent", null,
                MemoryScope.AGENT, 0.5, null, Set.of(), now, null)).block();

        String result = SessionMemoryEnricher.buildMemorySection(store);
        // High priority should appear before medium and low
        int highIdx = result.indexOf("High priority recent");
        int medIdx = result.indexOf("Medium priority recent");
        int lowIdx = result.indexOf("Low priority old");
        assertThat(highIdx).isLessThan(medIdx);
        assertThat(medIdx).isLessThan(lowIdx);
    }
}
