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

import io.kairo.api.memory.MemoryEntry;
import io.kairo.api.memory.MemoryScope;
import io.kairo.api.memory.MemoryStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Garbage-collects stale memory entries based on type and age.
 *
 * <p>Policy:
 * <ul>
 *   <li>Entries tagged with {@code auto:preference} or {@code auto:feedback} never expire
 *       — they represent learned user corrections that should persist indefinitely.</li>
 *   <li>Entries tagged with {@code auto:fact} or {@code auto:project} expire after {@code maxAge}
 *       (default 30 days) — project facts become stale as the codebase evolves.</li>
 *   <li>Manually added entries (no {@code auto:} tag) never expire.</li>
 * </ul>
 */
public class MemoryGarbageCollector {

    private static final Logger log = LoggerFactory.getLogger(MemoryGarbageCollector.class);

    private static final Set<String> NEVER_EXPIRE_TAGS =
            Set.of("auto:preference", "auto:feedback");

    private static final Set<String> EXPIRABLE_TAGS =
            Set.of("auto:fact", "auto:project");

    private final MemoryStore store;
    private final Duration maxAge;

    public MemoryGarbageCollector(MemoryStore store) {
        this(store, Duration.ofDays(30));
    }

    public MemoryGarbageCollector(MemoryStore store, Duration maxAge) {
        this.store = store;
        this.maxAge = maxAge;
    }

    public Mono<Integer> collect() {
        Instant cutoff = Instant.now().minus(maxAge);
        return store.list(MemoryScope.AGENT)
                .concatWith(store.list(MemoryScope.GLOBAL))
                .filter(entry -> shouldExpire(entry, cutoff))
                .flatMap(entry -> store.delete(entry.id()).thenReturn(entry.id()))
                .doOnNext(id -> log.debug("GC: expired memory entry {}", id))
                .collectList()
                .map(deleted -> {
                    if (!deleted.isEmpty()) {
                        log.info("Memory GC: removed {} expired entries", deleted.size());
                    }
                    return deleted.size();
                });
    }

    private boolean shouldExpire(MemoryEntry entry, Instant cutoff) {
        if (entry.timestamp() == null || entry.timestamp().isAfter(cutoff)) {
            return false;
        }
        if (entry.tags() == null || entry.tags().isEmpty()) {
            return false;
        }
        if (entry.tags().stream().anyMatch(NEVER_EXPIRE_TAGS::contains)) {
            return false;
        }
        return entry.tags().stream().anyMatch(EXPIRABLE_TAGS::contains);
    }
}
