/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kairo.code.core.team.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.kairo.api.team.TeamEvent;
import io.kairo.api.team.TeamEventType;
import io.kairo.api.team.TeamStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * File-system-backed {@link TeamRepository} using JSON files.
 *
 * <p>Storage layout under {@code baseDir}:
 * <pre>
 * {teamId}/
 * ├── manifest.json          # team metadata + DAG + status (single-writer)
 * ├── step-{stepId}.json     # per-step outcome (each step writes only its own file)
 * └── events.jsonl           # append-only event log with seq + timestamp
 * </pre>
 *
 * <p>Thread-safety guarantees:
 * <ul>
 *   <li><b>manifest.json</b>: written on a dedicated {@link Schedulers#single()} scheduler
 *       to enforce single-writer semantics.
 *   <li><b>step-{stepId}.json</b>: each step writes only its own file, so parallel steps
 *       never contend.
 *   <li><b>events.jsonl</b>: append-only; a per-team {@link AtomicLong} tracks the next
 *       sequence number, and file writes are serialized on the manifest scheduler.
 * </ul>
 */
public class JsonFileTeamRepository implements TeamRepository {

    private static final Logger log = LoggerFactory.getLogger(JsonFileTeamRepository.class);

    private static final String MANIFEST_FILE = "manifest.json";
    private static final String EVENTS_FILE = "events.jsonl";
    private static final String STEP_FILE_PREFIX = "step-";
    private static final String JSON_SUFFIX = ".json";

    private static final Set<TeamStatus> TERMINAL_STATUSES =
            Set.of(TeamStatus.COMPLETED, TeamStatus.FAILED, TeamStatus.CANCELLED, TeamStatus.TIMEOUT);

    private final Path baseDir;
    private final ObjectMapper mapper;
    private final Scheduler manifestScheduler;

    /**
     * Per-team sequence counter for event log entries. Lazily initialized on first append;
     * pre-populated from the existing events.jsonl on load.
     */
    private final Map<String, AtomicLong> seqCounters = new HashMap<>();

    /**
     * Creates a repository rooted at the given base directory.
     *
     * @param baseDir root directory under which team directories are created
     */
    public JsonFileTeamRepository(Path baseDir) {
        this(baseDir, Schedulers.newSingle("team-manifest-writer", true));
    }

    /**
     * Creates a repository with an explicit manifest-writer scheduler (useful for testing).
     *
     * @param baseDir root directory
     * @param manifestScheduler scheduler for manifest and event-log writes
     */
    public JsonFileTeamRepository(Path baseDir, Scheduler manifestScheduler) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir must not be null");
        this.manifestScheduler = Objects.requireNonNull(manifestScheduler, "manifestScheduler");
        this.mapper = createObjectMapper();
    }

    // ---- TeamRepository contract ----

    @Override
    public Mono<Void> saveManifest(String teamId, TeamManifest manifest) {
        return Mono.<Void>fromRunnable(() -> {
            Path teamDir = ensureTeamDir(teamId);
            Path target = teamDir.resolve(MANIFEST_FILE);
            writeJson(target, manifest);
            log.debug("Saved manifest for team {}", teamId);
        }).subscribeOn(manifestScheduler);
    }

    @Override
    public Mono<Void> saveStepOutcome(String teamId, String stepId, StepOutcomeRecord outcome) {
        // Step files are written by the step's own completion handler — no scheduler needed
        // since each step writes a different file.
        return Mono.fromRunnable(() -> {
            Path teamDir = ensureTeamDir(teamId);
            Path target = teamDir.resolve(STEP_FILE_PREFIX + stepId + JSON_SUFFIX);
            writeJson(target, outcome);
            log.debug("Saved step outcome for team {} step {}", teamId, stepId);
        });
    }

    @Override
    public Mono<Void> appendEvent(String teamId, TeamEvent event) {
        return Mono.<Void>fromRunnable(() -> {
            Path teamDir = ensureTeamDir(teamId);
            Path target = teamDir.resolve(EVENTS_FILE);
            long seq = nextSeq(teamId, target);
            EventLine line = new EventLine(seq, event.type(), event.teamId(),
                    event.requestId(), event.timestamp(), event.attributes());
            String json;
            try {
                json = mapper.writeValueAsString(line);
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(new IOException(
                        "Failed to serialize event for team " + teamId, e));
            }
            try {
                Files.writeString(target, json + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            log.debug("Appended event seq={} type={} for team {}", seq, event.type(), teamId);
        }).subscribeOn(manifestScheduler);
    }

    @Override
    public Mono<TeamManifest> loadManifest(String teamId) {
        return Mono.fromCallable(() -> {
            Path file = baseDir.resolve(teamId).resolve(MANIFEST_FILE);
            if (!Files.exists(file)) {
                return null;
            }
            return mapper.readValue(file.toFile(), TeamManifest.class);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<StepOutcomeRecord> loadStepOutcome(String teamId, String stepId) {
        return Mono.fromCallable(() -> {
            Path file = baseDir.resolve(teamId)
                    .resolve(STEP_FILE_PREFIX + stepId + JSON_SUFFIX);
            if (!Files.exists(file)) {
                return null;
            }
            return mapper.readValue(file.toFile(), StepOutcomeRecord.class);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<TeamEvent> loadEvents(String teamId) {
        return Flux.defer(() -> {
            Path file = baseDir.resolve(teamId).resolve(EVENTS_FILE);
            if (!Files.exists(file)) {
                return Flux.empty();
            }
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                return Flux.fromIterable(lines)
                        .filter(l -> !l.isBlank())
                        .map(this::parseEventLine);
            } catch (IOException e) {
                return Flux.error(new UncheckedIOException(e));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<TeamManifest> loadIncomplete() {
        return Flux.defer(() -> {
            if (!Files.isDirectory(baseDir)) {
                return Flux.empty();
            }
            try (Stream<Path> dirs = Files.list(baseDir)) {
                List<TeamManifest> results = new ArrayList<>();
                dirs.filter(Files::isDirectory).forEach(dir -> {
                    Path manifestFile = dir.resolve(MANIFEST_FILE);
                    if (Files.exists(manifestFile)) {
                        try {
                            TeamManifest m = mapper.readValue(
                                    manifestFile.toFile(), TeamManifest.class);
                            if (!TERMINAL_STATUSES.contains(m.status())) {
                                results.add(m);
                            }
                        } catch (IOException e) {
                            log.warn("Failed to read manifest from {}, skipping", manifestFile, e);
                        }
                    }
                });
                return Flux.fromIterable(results);
            } catch (IOException e) {
                return Flux.error(new UncheckedIOException(e));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> delete(String teamId) {
        return Mono.<Void>fromRunnable(() -> {
            Path teamDir = baseDir.resolve(teamId);
            if (!Files.isDirectory(teamDir)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(teamDir)) {
                // Sort in reverse so files are deleted before their parent directories
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            synchronized (seqCounters) {
                seqCounters.remove(teamId);
            }
            log.debug("Deleted all state for team {}", teamId);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ---- Internal helpers ----

    private Path ensureTeamDir(String teamId) {
        Path dir = baseDir.resolve(teamId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dir;
    }

    private void writeJson(Path target, Object value) {
        try {
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(value);
            // Atomic write: write to temp then move
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, bytes, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private long nextSeq(String teamId, Path eventsFile) {
        synchronized (seqCounters) {
            AtomicLong counter = seqCounters.get(teamId);
            if (counter == null) {
                long maxSeq = 0;
                if (Files.exists(eventsFile)) {
                    try {
                        List<String> lines = Files.readAllLines(
                                eventsFile, StandardCharsets.UTF_8);
                        for (String line : lines) {
                            if (line.isBlank()) continue;
                            EventLine el = mapper.readValue(line, EventLine.class);
                            if (el.seq() > maxSeq) {
                                maxSeq = el.seq();
                            }
                        }
                    } catch (IOException e) {
                        log.warn("Failed to scan events.jsonl for team {}, starting seq at 1",
                                teamId, e);
                    }
                }
                counter = new AtomicLong(maxSeq);
                seqCounters.put(teamId, counter);
            }
            return counter.incrementAndGet();
        }
    }

    private TeamEvent parseEventLine(String json) {
        try {
            EventLine line = mapper.readValue(json, EventLine.class);
            return new TeamEvent(line.type(), line.teamId(), line.requestId(),
                    line.timestamp(), line.attributes());
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException("Failed to parse event line: " + json, e));
        }
    }

    private static ObjectMapper createObjectMapper() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        om.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return om;
    }

    /**
     * Internal event-line envelope for the events.jsonl file.
     * Each line includes a monotonic sequence number for ordering.
     */
    record EventLine(
            long seq,
            TeamEventType type,
            String teamId,
            String requestId,
            Instant timestamp,
            Map<String, Object> attributes) {}
}
