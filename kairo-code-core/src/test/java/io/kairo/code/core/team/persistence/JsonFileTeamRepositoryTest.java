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

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.team.TeamEvent;
import io.kairo.api.team.TeamEventType;
import io.kairo.api.team.TeamStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class JsonFileTeamRepositoryTest {

    @TempDir
    Path tempDir;

    private JsonFileTeamRepository repo;

    @BeforeEach
    void setUp() {
        // Use immediate scheduler for deterministic tests
        repo = new JsonFileTeamRepository(tempDir, Schedulers.immediate());
    }

    // ---- Manifest ----

    @Test
    void saveAndLoadManifest() {
        TeamManifest manifest = sampleManifest("team-1", TeamStatus.DEGRADED);

        repo.saveManifest("team-1", manifest).block();
        TeamManifest loaded = repo.loadManifest("team-1").block();

        assertThat(loaded).isNotNull();
        assertThat(loaded.teamId()).isEqualTo("team-1");
        assertThat(loaded.goal()).isEqualTo("Build the feature");
        assertThat(loaded.status()).isEqualTo(TeamStatus.DEGRADED);
        assertThat(loaded.dag()).hasSize(2);
        assertThat(loaded.completedStepIds()).containsExactlyInAnyOrder("s1");
        assertThat(loaded.cost().spent()).isEqualTo(1.5);
        assertThat(loaded.cost().budget()).isEqualTo(10.0);
    }

    @Test
    void loadManifest_notFound_returnsEmpty() {
        StepVerifier.create(repo.loadManifest("nonexistent"))
                .verifyComplete();
    }

    // ---- Step Outcome ----

    @Test
    void saveAndLoadStepOutcome() {
        StepOutcomeRecord outcome = sampleOutcome("s1");

        repo.saveStepOutcome("team-1", "s1", outcome).block();
        StepOutcomeRecord loaded = repo.loadStepOutcome("team-1", "s1").block();

        assertThat(loaded).isNotNull();
        assertThat(loaded.stepId()).isEqualTo("s1");
        assertThat(loaded.output()).isEqualTo("Generated code");
        assertThat(loaded.verdictOutcome()).isEqualTo("PASS");
        assertThat(loaded.verdictScore()).isEqualTo(0.95);
        assertThat(loaded.attempts()).isEqualTo(2);
    }

    @Test
    void loadStepOutcome_notFound_returnsEmpty() {
        StepVerifier.create(repo.loadStepOutcome("team-1", "missing"))
                .verifyComplete();
    }

    // ---- Events ----

    @Test
    void appendAndLoadEvents_inOrder() {
        String teamId = "team-events";
        Instant now = Instant.now();

        TeamEvent e1 = new TeamEvent(TeamEventType.TEAM_STARTED, teamId, "req-1",
                now, Map.of("goal", "test"));
        TeamEvent e2 = new TeamEvent(TeamEventType.STEP_ASSIGNED, teamId, "req-1",
                now.plusSeconds(1), Map.of("stepId", "s1"));
        TeamEvent e3 = new TeamEvent(TeamEventType.STEP_COMPLETED, teamId, "req-1",
                now.plusSeconds(2), Map.of("stepId", "s1"));

        repo.appendEvent(teamId, e1).block();
        repo.appendEvent(teamId, e2).block();
        repo.appendEvent(teamId, e3).block();

        List<TeamEvent> events = repo.loadEvents(teamId).collectList().block();

        assertThat(events).hasSize(3);
        assertThat(events.get(0).type()).isEqualTo(TeamEventType.TEAM_STARTED);
        assertThat(events.get(1).type()).isEqualTo(TeamEventType.STEP_ASSIGNED);
        assertThat(events.get(2).type()).isEqualTo(TeamEventType.STEP_COMPLETED);
    }

    @Test
    void loadEvents_noFile_returnsEmpty() {
        StepVerifier.create(repo.loadEvents("no-team"))
                .verifyComplete();
    }

    @Test
    void events_appendOnly_preservesExistingLines() {
        String teamId = "team-append";
        Instant now = Instant.now();

        TeamEvent e1 = new TeamEvent(TeamEventType.TEAM_STARTED, teamId, "req-1",
                now, Map.of());
        repo.appendEvent(teamId, e1).block();

        // Verify file has one line
        Path eventsFile = tempDir.resolve(teamId).resolve("events.jsonl");
        assertThat(eventsFile).exists();

        TeamEvent e2 = new TeamEvent(TeamEventType.STEP_ASSIGNED, teamId, "req-1",
                now.plusSeconds(1), Map.of("step", "s1"));
        repo.appendEvent(teamId, e2).block();

        List<TeamEvent> events = repo.loadEvents(teamId).collectList().block();
        assertThat(events).hasSize(2);
    }

    // ---- Crash Recovery ----

    @Test
    void loadIncomplete_findsOnlyNonTerminalManifests() {
        repo.saveManifest("running", sampleManifest("running", TeamStatus.DEGRADED)).block();
        repo.saveManifest("done", sampleManifest("done", TeamStatus.COMPLETED)).block();
        repo.saveManifest("failed", sampleManifest("failed", TeamStatus.FAILED)).block();
        repo.saveManifest("timeout", sampleManifest("timeout", TeamStatus.TIMEOUT)).block();
        repo.saveManifest("cancelled", sampleManifest("cancelled", TeamStatus.CANCELLED)).block();

        List<TeamManifest> incomplete = repo.loadIncomplete().collectList().block();

        assertThat(incomplete).hasSize(1);
        assertThat(incomplete.get(0).teamId()).isEqualTo("running");
    }

    @Test
    void loadIncomplete_emptyBaseDir_returnsEmpty() {
        StepVerifier.create(repo.loadIncomplete())
                .verifyComplete();
    }

    // ---- Delete ----

    @Test
    void delete_removesEntireTeamDirectory() {
        String teamId = "team-del";
        repo.saveManifest(teamId, sampleManifest(teamId, TeamStatus.COMPLETED)).block();
        repo.saveStepOutcome(teamId, "s1", sampleOutcome("s1")).block();
        repo.appendEvent(teamId, new TeamEvent(TeamEventType.TEAM_STARTED, teamId, "req-1",
                Instant.now(), Map.of())).block();

        assertThat(tempDir.resolve(teamId)).isDirectory();

        repo.delete(teamId).block();

        assertThat(tempDir.resolve(teamId)).doesNotExist();
    }

    @Test
    void delete_nonExistent_completes() {
        StepVerifier.create(repo.delete("ghost"))
                .verifyComplete();
    }

    // ---- Concurrent Step Writes ----

    @Test
    void concurrentStepWrites_toDifferentFiles_noInterference() throws Exception {
        String teamId = "team-concurrent";
        int stepCount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(stepCount);
        CountDownLatch latch = new CountDownLatch(stepCount);

        for (int i = 0; i < stepCount; i++) {
            String stepId = "step-" + i;
            pool.submit(() -> {
                try {
                    repo.saveStepOutcome(teamId, stepId, sampleOutcome(stepId)).block();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // Verify all step files exist and are readable
        for (int i = 0; i < stepCount; i++) {
            String stepId = "step-" + i;
            StepOutcomeRecord loaded = repo.loadStepOutcome(teamId, stepId).block();
            assertThat(loaded).isNotNull();
            assertThat(loaded.stepId()).isEqualTo(stepId);
        }
    }

    // ---- Sequence Number Continuity ----

    @Test
    void eventSequenceNumbers_continueAfterReinstantiation() {
        String teamId = "team-seq";
        Instant now = Instant.now();

        repo.appendEvent(teamId, new TeamEvent(TeamEventType.TEAM_STARTED, teamId,
                "req-1", now, Map.of())).block();
        repo.appendEvent(teamId, new TeamEvent(TeamEventType.STEP_ASSIGNED, teamId,
                "req-1", now.plusSeconds(1), Map.of())).block();

        // Create a new repo instance pointing at the same directory
        JsonFileTeamRepository repo2 = new JsonFileTeamRepository(tempDir, Schedulers.immediate());
        repo2.appendEvent(teamId, new TeamEvent(TeamEventType.STEP_COMPLETED, teamId,
                "req-1", now.plusSeconds(2), Map.of())).block();

        // Verify the events.jsonl file has 3 lines with seq 1, 2, 3
        Path eventsFile = tempDir.resolve(teamId).resolve("events.jsonl");
        try {
            List<String> lines = Files.readAllLines(eventsFile);
            assertThat(lines).hasSize(3);
            assertThat(lines.get(0)).contains("\"seq\":1");
            assertThat(lines.get(1)).contains("\"seq\":2");
            assertThat(lines.get(2)).contains("\"seq\":3");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- Manifest Atomic Write ----

    @Test
    void manifestUpdate_isAtomic_noTmpFileLeftBehind() {
        String teamId = "team-atomic";
        repo.saveManifest(teamId, sampleManifest(teamId, TeamStatus.DEGRADED)).block();

        // Update manifest
        TeamManifest updated = sampleManifest(teamId, TeamStatus.COMPLETED);
        repo.saveManifest(teamId, updated).block();

        // No .tmp files should remain
        Path teamDir = tempDir.resolve(teamId);
        try (var files = Files.list(teamDir)) {
            assertThat(files.noneMatch(p -> p.toString().endsWith(".tmp"))).isTrue();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        TeamManifest loaded = repo.loadManifest(teamId).block();
        assertThat(loaded.status()).isEqualTo(TeamStatus.COMPLETED);
    }

    // ---- Helpers ----

    private static TeamManifest sampleManifest(String teamId, TeamStatus status) {
        return new TeamManifest(
                teamId,
                "Build the feature",
                status,
                List.of(
                        new TeamManifest.StepEntry("s1", "Code it", "coder",
                                List.of(), 0),
                        new TeamManifest.StepEntry("s2", "Review it", "reviewer",
                                List.of("s1"), 1)),
                Set.of("s1"),
                new TeamManifest.CostSnapshot(1.5, 10.0),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:05:00Z"));
    }

    private static StepOutcomeRecord sampleOutcome(String stepId) {
        return new StepOutcomeRecord(
                stepId,
                "Generated code",
                "PASS",
                0.95,
                "Looks good",
                List.of("Minor style fix"),
                2,
                Instant.parse("2026-01-01T00:03:00Z"));
    }
}
