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
package io.kairo.code.server.config;

import io.kairo.api.agent.Agent;
import io.kairo.api.event.KairoEventBus;
import io.kairo.api.team.MessageBus;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.code.core.team.TeamManager;
import io.kairo.code.core.team.persistence.JsonFileTeamRepository;
import io.kairo.code.core.team.persistence.TeamRepository;
import io.kairo.code.core.team.tools.ExpertTeamTool;
import io.kairo.core.a2a.InProcessA2aClient;
import io.kairo.core.a2a.InProcessAgentCardResolver;
import io.kairo.expertteam.ExpertTeamCoordinator;
import io.kairo.expertteam.SimpleEvaluationStrategy;
import io.kairo.expertteam.internal.DefaultPlanner;
import io.kairo.expertteam.memory.ExpertMemoryStore;
import io.kairo.expertteam.role.ExpertRoleRegistry;
import io.kairo.expertteam.strategy.SynthesizerStep;
import io.kairo.multiagent.a2a.TeamAwareA2aClient;
import io.kairo.multiagent.team.DefaultTeamManager;
import io.kairo.multiagent.team.InProcessMessageBus;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the expert-team plan→generate→evaluate coordinator (ADR-015)
 * and the thin SwarmCoordinator shell that delegates to it.
 */
@Configuration
public class TeamConfig {

    @Bean
    public TeamManager teamManager() {
        return new TeamManager();
    }

    // ── A2A Infrastructure ──

    @Bean
    public InProcessAgentCardResolver agentCardResolver() {
        return new InProcessAgentCardResolver();
    }

    @Bean
    public InProcessA2aClient inProcessA2aClient(InProcessAgentCardResolver resolver) {
        return new InProcessA2aClient(resolver);
    }

    @Bean
    public MessageBus messageBus() {
        return new InProcessMessageBus();
    }

    @Bean
    public io.kairo.api.team.TeamManager apiTeamManager(
            InProcessAgentCardResolver resolver) {
        return new DefaultTeamManager(resolver);
    }

    @Bean
    public TeamAwareA2aClient teamAwareA2aClient(
            InProcessA2aClient a2aClient,
            InProcessAgentCardResolver resolver,
            io.kairo.api.team.TeamManager apiTeamManager) {
        return new TeamAwareA2aClient(a2aClient, resolver, apiTeamManager, "expert-team");
    }

    // ── Expert Team (ADR-015) ──

    @Bean
    public ExpertRoleRegistry expertRoleRegistry() {
        return new ExpertRoleRegistry();
    }

    @Bean
    public ExpertMemoryStore expertMemoryStore() {
        return new ExpertMemoryStore();
    }

    @Bean
    public DefaultPlanner expertTeamPlanner(ExpertRoleRegistry registry) {
        // Deterministic planner with registry — no LLM planning agent wired yet.
        return new DefaultPlanner(registry, null, null);
    }

    @Bean
    public ExpertTeamCoordinator expertTeamCoordinator(
            ExpertRoleRegistry registry,
            DefaultPlanner planner,
            MessageBus messageBus,
            @Autowired(required = false) KairoEventBus eventBus,
            @Autowired(required = false) SynthesizerStep synthesizer) {
        return new ExpertTeamCoordinator(
                eventBus,
                new SimpleEvaluationStrategy(),
                null, // no agent-based evaluator yet
                planner,
                registry,
                messageBus,
                null, // no arbitrator yet
                synthesizer);
    }

    @Bean
    public SwarmCoordinator swarmCoordinator(
            ExpertTeamCoordinator coordinator,
            ExpertRoleRegistry registry,
            MessageBus messageBus,
            @Autowired(required = false) List<Agent> agents) {
        return new SwarmCoordinator(
                coordinator,
                registry,
                messageBus,
                agents != null ? agents : List.of());
    }

    @Bean
    public ExpertTeamTool expertTeamTool(SwarmCoordinator swarmCoordinator) {
        return new ExpertTeamTool(swarmCoordinator);
    }

    // ── Persistence (crash recovery) ──

    @Bean
    public TeamRepository teamRepository() {
        Path teamsDir = Paths.get(System.getProperty("user.home"), ".kairo-code", "teams");
        return new JsonFileTeamRepository(teamsDir);
    }
}
