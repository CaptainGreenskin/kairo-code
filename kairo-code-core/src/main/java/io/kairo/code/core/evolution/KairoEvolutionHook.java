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
package io.kairo.code.core.evolution;

import io.kairo.api.agent.AgentState;
import io.kairo.api.evolution.EvolvedSkillStore;
import io.kairo.api.evolution.EvolutionContext;
import io.kairo.api.evolution.EvolvedSkill;
import io.kairo.api.hook.OnSessionEnd;
import io.kairo.api.hook.SessionEndEvent;
import io.kairo.api.message.Msg;
import io.kairo.api.evolution.EvolutionCounters;
import io.kairo.evolution.EvolutionPipelineOrchestrator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * kairo-code-specific evolution hook with configurable minimum iteration threshold.
 * The framework's EvolutionHook hardcodes min=3, which is too high for GLM's
 * streaming pre-exec mode where multiple tool calls execute in a single iteration.
 */
public final class KairoEvolutionHook {

    private static final Logger LOG = LoggerFactory.getLogger(KairoEvolutionHook.class);

    private final EvolvedSkillStore skillStore;
    private final EvolutionPipelineOrchestrator orchestrator;
    private final int minIterations;

    public KairoEvolutionHook(
            EvolvedSkillStore skillStore,
            EvolutionPipelineOrchestrator orchestrator,
            int minIterations) {
        this.skillStore = skillStore;
        this.orchestrator = orchestrator;
        this.minIterations = minIterations;
    }

    @OnSessionEnd(order = 100)
    public void onSessionEnd(SessionEndEvent event) {
        if (event.finalState() != AgentState.COMPLETED) {
            LOG.debug("Evolution skip: session not completed (state={})", event.finalState());
            return;
        }
        if (event.iterations() < minIterations) {
            LOG.debug("Evolution skip: too few iterations ({} < {})",
                    event.iterations(), minIterations);
            return;
        }

        List<Msg> history = event.conversationHistorySupplier() != null
                ? event.conversationHistorySupplier().get() : List.of();
        if (history.isEmpty()) {
            LOG.debug("Evolution skip: empty conversation history");
            return;
        }

        List<EvolvedSkill> existingSkills =
                skillStore.list().collectList().blockOptional().orElse(List.of());

        EvolutionContext context = new EvolutionContext(
                event.agentName(), history, event.iterations(),
                EvolutionCounters.ZERO, 5, 8, event.tokensUsed(), existingSkills);

        LOG.info("Submitting evolution review for agent '{}' ({} iterations, {} messages)",
                event.agentName(), event.iterations(), history.size());

        orchestrator.submit(context)
                .subscribe(
                        unused -> {},
                        err -> LOG.warn("Evolution review failed for agent '{}': {}",
                                event.agentName(), err.getMessage()),
                        () -> LOG.info("Evolution review completed for agent '{}'",
                                event.agentName()));
    }
}
