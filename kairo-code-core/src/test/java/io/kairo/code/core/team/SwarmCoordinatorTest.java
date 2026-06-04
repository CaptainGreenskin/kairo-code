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
package io.kairo.code.core.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.kairo.api.team.TeamConfig;
import io.kairo.api.team.TeamResult;
import io.kairo.multiagent.orchestration.ExpertTeamCoordinator;
import io.kairo.multiagent.orchestration.SimpleEvaluationStrategy;
import io.kairo.multiagent.orchestration.internal.DefaultPlanner;
import io.kairo.multiagent.subagent.ExpertRoleRegistry;
import io.kairo.multiagent.orchestration.tck.NoopMessageBus;
import io.kairo.multiagent.orchestration.tck.StubAgent;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class SwarmCoordinatorTest {

    private SwarmCoordinator coordinator;
    private ExpertRoleRegistry roleRegistry;

    @BeforeEach
    void setUp() {
        roleRegistry = new ExpertRoleRegistry();
        DefaultPlanner planner = new DefaultPlanner(roleRegistry, null, null);
        ExpertTeamCoordinator expertCoordinator = new ExpertTeamCoordinator(
                null, new SimpleEvaluationStrategy(), null, planner, roleRegistry);

        coordinator = new SwarmCoordinator(
                expertCoordinator,
                roleRegistry,
                new NoopMessageBus(),
                List.of(StubAgent.fixed("worker", "done")));
    }

    @Test
    void startExpertTeamDelegatesToCoordinator() {
        Mono<TeamResult> resultMono = coordinator.startExpertTeam(
                "refactor auth module", TeamConfig.defaults(), List.of());

        TeamResult result = resultMono.block();
        assertNotNull(result);
        assertNotNull(result.requestId());
        assertNotNull(result.status());
    }

    @Test
    void startExpertTeamConvenienceOverload() {
        Mono<TeamResult> resultMono = coordinator.startExpertTeam("build a feature");

        TeamResult result = resultMono.block();
        assertNotNull(result);
        assertNotNull(result.requestId());
    }

    @Test
    void emptyRoleIdsUsesAllRegisteredProfiles() {
        Mono<TeamResult> resultMono = coordinator.startExpertTeam(
                "analyze codebase", TeamConfig.defaults(), List.of());

        TeamResult result = resultMono.block();
        assertNotNull(result);
        assertNotNull(result.stepOutcomes());
    }

    @Test
    void roleRegistryIsExposed() {
        ExpertRoleRegistry registry = coordinator.roleRegistry();
        assertNotNull(registry);
        assertEquals(roleRegistry, registry);
    }

    @Test
    void constructorRejectsNullCoordinator() {
        assertThrows(NullPointerException.class, () ->
                new SwarmCoordinator(null, roleRegistry, new NoopMessageBus(), List.of()));
    }

    @Test
    void constructorRejectsNullRegistry() {
        ExpertTeamCoordinator expertCoordinator = new ExpertTeamCoordinator(null);
        assertThrows(NullPointerException.class, () ->
                new SwarmCoordinator(expertCoordinator, null, new NoopMessageBus(), List.of()));
    }

    @Test
    void constructorRejectsNullMessageBus() {
        ExpertTeamCoordinator expertCoordinator = new ExpertTeamCoordinator(null);
        assertThrows(NullPointerException.class, () ->
                new SwarmCoordinator(expertCoordinator, roleRegistry, null, List.of()));
    }
}
