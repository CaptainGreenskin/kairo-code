/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.kairo.code.core.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.memory.MemoryStore;
import io.kairo.code.core.prompt.SessionMemoryEnricher;
import io.kairo.core.memory.FileMemoryStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryPersistenceIT {

    @TempDir Path tempDir;

    @Test
    void memoryPersistsAcrossSessions() {
        Path memoryDir = tempDir.resolve("memory");

        MemoryStore store1 = new FileMemoryStore(memoryDir);
        MemoryEntry entry = new MemoryEntry(
                "test-fact-01", "kairo-code",
                "User prefers Google Java Format with 4-space indent",
                null, MemoryScope.AGENT, 0.9, null,
                Set.of("auto:preference"), Instant.now(), null);
        store1.save(entry).block();

        MemoryStore store2 = new FileMemoryStore(memoryDir);
        String memorySection = SessionMemoryEnricher.buildMemorySection(store2);
        assertThat(memorySection)
                .contains("Google Java Format")
                .contains("4-space indent");
    }

    @Test
    void gcRemovesExpiredProjectFacts() {
        Path memoryDir = tempDir.resolve("memory");
        MemoryStore store = new FileMemoryStore(memoryDir);

        MemoryEntry oldFact = new MemoryEntry(
                "old-fact", "kairo-code",
                "Project uses Spring Boot 3.2",
                null, MemoryScope.AGENT, 0.6, null,
                Set.of("auto:fact"),
                Instant.now().minusSeconds(31 * 86400), null);
        store.save(oldFact).block();

        MemoryEntry oldPref = new MemoryEntry(
                "old-pref", "kairo-code",
                "User prefers concise comments",
                null, MemoryScope.AGENT, 0.8, null,
                Set.of("auto:preference"),
                Instant.now().minusSeconds(31 * 86400), null);
        store.save(oldPref).block();

        MemoryEntry recentFact = new MemoryEntry(
                "recent-fact", "kairo-code",
                "Project migrated to Java 21",
                null, MemoryScope.AGENT, 0.7, null,
                Set.of("auto:fact"),
                Instant.now().minusSeconds(86400), null);
        store.save(recentFact).block();

        MemoryGarbageCollector gc = new MemoryGarbageCollector(store);
        int deleted = gc.collect().block();

        assertThat(deleted).isEqualTo(1);
        assertThat(store.get("old-fact").block()).isNull();
        assertThat(store.get("old-pref").block()).isNotNull();
        assertThat(store.get("recent-fact").block()).isNotNull();
    }
}
