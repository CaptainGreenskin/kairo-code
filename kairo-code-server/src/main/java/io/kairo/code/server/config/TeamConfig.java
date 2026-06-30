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
import io.kairo.api.agent.AgentBuilderCustomizer;
import io.kairo.api.event.KairoEventBus;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.team.MessageBus;
import io.kairo.api.tool.ApprovalResult;
import io.kairo.api.tool.ToolExecutor;
import io.kairo.api.tool.UserApprovalHandler;
import io.kairo.api.tool.ToolRegistry;
import io.kairo.api.workspace.Workspace;
import io.kairo.api.team.TeamManager;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.code.core.team.persistence.JsonFileTeamRepository;
import io.kairo.code.core.team.persistence.TeamRepository;
import io.kairo.core.a2a.InProcessA2aClient;
import io.kairo.core.a2a.InProcessAgentCardResolver;
import io.kairo.core.agent.AgentBuilder;
import io.kairo.core.shutdown.GracefulShutdownManager;
import io.kairo.multiagent.orchestration.ExpertTeamCoordinator;
import io.kairo.multiagent.orchestration.SimpleEvaluationStrategy;
import io.kairo.multiagent.orchestration.internal.DefaultPlanner;
import io.kairo.multiagent.orchestration.ExpertMemoryStore;
import io.kairo.multiagent.subagent.ExpertRoleRegistry;
import io.kairo.multiagent.orchestration.SynthesizerStep;
import io.kairo.multiagent.a2a.TeamAwareA2aClient;
import io.kairo.multiagent.team.DefaultTeamManager;
import io.kairo.multiagent.team.InProcessMessageBus;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Wires the expert-team plan→generate→evaluate coordinator (ADR-015)
 * and the thin SwarmCoordinator shell that delegates to it.
 */
@Configuration
public class TeamConfig {

    @Bean
    public TeamManager teamManager(InProcessAgentCardResolver resolver) {
        return new DefaultTeamManager(resolver);
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
    public DefaultPlanner expertTeamPlanner(
            ExpertRoleRegistry registry,
            ModelProvider modelProvider,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            GracefulShutdownManager shutdownManager,
            @Autowired(required = false) List<AgentBuilderCustomizer> customizers) {
        // LLM planning agent: a tool-free, workspace-free agent that decomposes the goal into a
        // multi-role DAG (architect/coder/reviewer/tester …). Wrapped in StatelessAgentProxy so
        // each planning call gets a clean context. Customizers must be applied to satisfy the
        // mandatory modelName (set by ServerConfig.modelNameCustomizer).
        Agent planningAgent = new StatelessAgentProxy("expert-planner", () -> {
            AgentBuilder builder = AgentBuilder.create()
                    .name("expert-planner")
                    .model(modelProvider)
                    .tools(toolRegistry)
                    .toolExecutor(toolExecutor)
                    .shutdownManager(shutdownManager)
                    .systemPrompt(
                            "You are an expert-team task planner. Decompose the goal into role-assigned "
                                    + "steps ONLY when decomposition genuinely helps. For simple tasks "
                                    + "(read a file, write a doc, fix a bug), output a SINGLE step — "
                                    + "do not over-decompose. Choose roles based on what the task "
                                    + "actually needs (coder for writing, researcher for analysis). "
                                    + "Write specific, actionable step instructions with concrete "
                                    + "deliverables. Output ONLY the JSON array — no prose, "
                                    + "no code fences.");
            if (customizers != null) {
                customizers.forEach(c -> c.customize(builder));
            }
            return builder.build();
        });
        return new DefaultPlanner(registry, planningAgent, null);
    }

    @Bean
    public ExpertTeamCoordinator expertTeamCoordinator(
            ExpertRoleRegistry registry,
            DefaultPlanner planner,
            MessageBus messageBus,
            ExpertMemoryStore memoryStore,
            ModelProvider modelProvider,
            ServerConfig.ServerProperties serverProperties,
            @Autowired(required = false) KairoEventBus eventBus,
            @Autowired(required = false) SynthesizerStep synthesizer) {
        ExpertTeamCoordinator coordinator = new ExpertTeamCoordinator(
                eventBus,
                new SimpleEvaluationStrategy(),
                null, // no agent-based evaluator yet
                planner,
                registry,
                messageBus,
                null, // no arbitrator yet
                synthesizer,
                memoryStore,
                new io.kairo.code.core.team.LlmLessonExtractor(
                        modelProvider, serverProperties.model()));
        // Wire workspace-context injection: gathers a per-session workspace snapshot once per
        // execution and feeds it to worker prompts by role scope (see DefaultGenerator).
        coordinator.setWorkspaceContextGatherer(
                new io.kairo.code.core.team.SessionWorkspaceContextGatherer());
        return coordinator;
    }

    @Bean
    public SwarmCoordinator swarmCoordinator(
            ExpertTeamCoordinator coordinator,
            ExpertRoleRegistry registry,
            MessageBus messageBus,
            ModelProvider modelProvider,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            GracefulShutdownManager shutdownManager,
            @Autowired(required = false) List<AgentBuilderCustomizer> customizers) {
        AtomicReference<SwarmCoordinator> coordRef = new AtomicReference<>();
        // Experts run autonomously (qoder model): auto-approve tool calls so workers can run
        // bash/pytest etc. without a per-call approval handler (the worker has no UI to ask).
        UserApprovalHandler autoApprove = req -> Mono.just(ApprovalResult.allow());
        Agent workerProxy = new StatelessAgentProxy("expert-worker", () -> {
            SwarmCoordinator sc = coordRef.get();
            Workspace ws = sc != null ? sc.getActiveWorkspace() : null;
            // Defense: never let tools fall back to the JVM launch dir if the session
            // workspace wasn't bound yet.
            if (ws == null) {
                ws = Workspace.cwd();
            }
            final Workspace workspace = ws;
            AgentBuilder builder = AgentBuilder.create()
                    .name("expert-worker")
                    .workspace(workspace)
                    .model(modelProvider)
                    .tools(toolRegistry)
                    .toolExecutor(toolExecutor)
                    .approvalHandler(autoApprove)
                    // Tell the worker its real working directory so it uses correct paths
                    // instead of hallucinating absolutes like /home/user/project or /workspace.
                    .systemPrompt(
                            "You are an expert worker on a software team. Your current working "
                                    + "directory is: " + workspace.root()
                                    + "\nAll file paths you read or write must be relative to this "
                                    + "directory (or absolute under it). Never invent paths such as "
                                    + "/home/user/project or /workspace.")
                    .shutdownManager(shutdownManager);
            if (customizers != null) {
                customizers.forEach(c -> c.customize(builder));
            }
            return builder.build();
        });
        // L2 team self-evolution: record successful expert compositions and recall them at
        // planning time so the planner reuses what worked for similar tasks. Same coordinator
        // instance that confirmAndExecute runs on, so the store is live for every experts session.
        coordinator.setTeamPatternStore(
                new io.kairo.multiagent.orchestration.TeamPatternStore());
        SwarmCoordinator sc = new SwarmCoordinator(
                coordinator,
                registry,
                messageBus,
                List.of(workerProxy));
        coordRef.set(sc);
        return sc;
    }

    // ── Persistence (crash recovery) ──

    @Bean
    public TeamRepository teamRepository() {
        Path teamsDir = Paths.get(System.getProperty("user.home"), ".kairo-code", "teams");
        return new JsonFileTeamRepository(teamsDir);
    }
}
