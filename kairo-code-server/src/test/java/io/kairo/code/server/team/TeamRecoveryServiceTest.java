/*
 * Copyright 2025-2026 the Kairo authors.
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
package io.kairo.code.server.team;

import io.kairo.api.team.TeamEvent;
import io.kairo.api.team.TeamResult;
import io.kairo.api.team.TeamStatus;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.code.core.team.persistence.StepOutcomeRecord;
import io.kairo.code.core.team.persistence.TeamManifest;
import io.kairo.code.core.team.persistence.TeamManifest.CostSnapshot;
import io.kairo.code.core.team.persistence.TeamManifest.StepEntry;
import io.kairo.code.core.team.persistence.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TeamRecoveryService}.
 */
class TeamRecoveryServiceTest {

    private StubTeamRepository repository;
    private SwarmCoordinator coordinator;
    private TeamRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        repository = new StubTeamRepository();
        coordinator = mock(SwarmCoordinator.class);
        recoveryService = new TeamRecoveryService(repository, coordinator);
    }

    @Test
    void noIncompleteTeams_nothingHappens() {
        // repository returns empty flux by default
        assertThatNoException().isThrownBy(() -> recoveryService.recoverInFlightTeams());
        verify(coordinator, never()).resumeFromManifest(any());
    }

    @Test
    void oneIncompleteTeam_resumeFromManifestCalled() {
        TeamManifest manifest = createManifest("team-1", TeamStatus.DEGRADED,
                List.of(step("s1"), step("s2")), Set.of("s1"));
        repository.setIncompleteManifests(List.of(manifest));

        when(coordinator.resumeFromManifest(any())).thenReturn(
                Mono.just(TeamResult.withoutOutput(
                        "team-1", TeamStatus.COMPLETED, List.of(), Duration.ZERO, List.of())));

        recoveryService.recoverInFlightTeams();

        // Give the async subscribe a moment to complete
        await();
        verify(coordinator).resumeFromManifest(manifest);
    }

    @Test
    void teamWithAllStepsCompleted_resumeStillCalled() {
        // Even if all steps are complete, SwarmCoordinator.resumeFromManifest handles it
        // by returning COMPLETED immediately (no re-run)
        TeamManifest manifest = createManifest("team-2", TeamStatus.DEGRADED,
                List.of(step("s1"), step("s2")), Set.of("s1", "s2"));
        repository.setIncompleteManifests(List.of(manifest));

        when(coordinator.resumeFromManifest(any())).thenReturn(
                Mono.just(TeamResult.withoutOutput(
                        "team-2", TeamStatus.COMPLETED, List.of(), Duration.ZERO, List.of())));

        recoveryService.recoverInFlightTeams();

        await();
        verify(coordinator).resumeFromManifest(manifest);
    }

    @Test
    void repositoryError_doesNotCrashStartup() {
        repository.setErrorOnLoadIncomplete(new RuntimeException("disk failure"));

        assertThatNoException().isThrownBy(() -> recoveryService.recoverInFlightTeams());
        verify(coordinator, never()).resumeFromManifest(any());
    }

    // ── Helpers ──

    private static StepEntry step(String stepId) {
        return new StepEntry(stepId, "description for " + stepId, "role-1", List.of(), 0);
    }

    private static TeamManifest createManifest(String teamId, TeamStatus status,
                                                List<StepEntry> dag, Set<String> completedStepIds) {
        return new TeamManifest(
                teamId, "test goal", status, dag, completedStepIds,
                new CostSnapshot(0.0, 10.0), Instant.now(), Instant.now());
    }

    private static void await() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Stubs ──

    /**
     * Stub TeamRepository that returns configurable incomplete manifests.
     */
    private static class StubTeamRepository implements TeamRepository {

        private List<TeamManifest> incompleteManifests = List.of();
        private RuntimeException errorOnLoadIncomplete;

        void setIncompleteManifests(List<TeamManifest> manifests) {
            this.incompleteManifests = manifests;
        }

        void setErrorOnLoadIncomplete(RuntimeException error) {
            this.errorOnLoadIncomplete = error;
        }

        @Override
        public Flux<TeamManifest> loadIncomplete() {
            if (errorOnLoadIncomplete != null) {
                return Flux.error(errorOnLoadIncomplete);
            }
            return Flux.fromIterable(incompleteManifests);
        }

        @Override
        public Mono<Void> saveManifest(String teamId, TeamManifest manifest) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> saveStepOutcome(String teamId, String stepId, StepOutcomeRecord outcome) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> appendEvent(String teamId, TeamEvent event) {
            return Mono.empty();
        }

        @Override
        public Mono<TeamManifest> loadManifest(String teamId) {
            return Mono.empty();
        }

        @Override
        public Mono<StepOutcomeRecord> loadStepOutcome(String teamId, String stepId) {
            return Mono.empty();
        }

        @Override
        public Flux<TeamEvent> loadEvents(String teamId) {
            return Flux.empty();
        }

        @Override
        public Mono<Void> delete(String teamId) {
            return Mono.empty();
        }
    }
}
