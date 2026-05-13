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

import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.code.core.team.persistence.TeamManifest;
import io.kairo.code.core.team.persistence.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Recovers in-flight team executions that were interrupted by a crash (e.g. kill -9).
 *
 * <p>On {@link ApplicationReadyEvent}, scans for team manifests with non-terminal status
 * and resumes them asynchronously. Per crash recovery semantics:
 * <ul>
 *   <li>Steps in {@link TeamManifest#completedStepIds()} are done — their outputs are persisted
 *   <li>Steps NOT in completedStepIds are re-run from scratch (LLM context lost)
 *   <li>Completed step outputs serve as dependency inputs for downstream steps
 * </ul>
 *
 * <p>Recovery is non-blocking and does not delay application startup.
 */
@Component
public class TeamRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(TeamRecoveryService.class);

    private final TeamRepository teamRepository;
    private final SwarmCoordinator swarmCoordinator;

    public TeamRecoveryService(TeamRepository teamRepository, SwarmCoordinator swarmCoordinator) {
        this.teamRepository = teamRepository;
        this.swarmCoordinator = swarmCoordinator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInFlightTeams() {
        teamRepository.loadIncomplete()
                .doOnNext(manifest -> log.info(
                        "Recovering incomplete team '{}': {}/{} steps completed",
                        manifest.teamId(),
                        manifest.completedStepIds().size(),
                        manifest.dag().size()))
                .flatMap(this::resumeTeam)
                .doOnError(e -> log.error("Team recovery failed", e))
                .subscribe();
    }

    private reactor.core.publisher.Mono<Void> resumeTeam(TeamManifest manifest) {
        return swarmCoordinator.resumeFromManifest(manifest)
                .doOnSuccess(result -> log.info(
                        "Team '{}' recovery complete: status={}",
                        manifest.teamId(), result.status()))
                .then();
    }
}
