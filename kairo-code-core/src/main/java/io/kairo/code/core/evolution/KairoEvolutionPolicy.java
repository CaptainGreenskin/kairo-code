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

import io.kairo.api.evolution.EvolutionContext;
import io.kairo.api.evolution.EvolutionOutcome;
import io.kairo.api.evolution.EvolutionPolicy;
import io.kairo.api.evolution.EvolvedSkill;
import io.kairo.api.evolution.EvolvedSkillStore;
import io.kairo.api.evolution.SkillTrustLevel;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Coding-agent-specific evolution policy with a 4-level preference order that
 * prioritizes patching existing skills over creating new ones.
 *
 * <p>Preference order (prefer the earliest action that fits):
 * <ol>
 *   <li>PATCH a currently-loaded skill that already covers this topic</li>
 *   <li>UPDATE an existing umbrella skill with new learnings</li>
 *   <li>ADD a support file under an existing skill's references</li>
 *   <li>CREATE a new class-level umbrella skill (only if none of the above fit)</li>
 * </ol>
 */
public final class KairoEvolutionPolicy implements EvolutionPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(KairoEvolutionPolicy.class);

    private static final String SKILL_REVIEW_PROMPT =
            """
            You are extracting reusable knowledge from a coding agent session.
            Your goal is to find patterns worth saving for future sessions.

            ## When to CREATE a skill:
            - Any repeatable workflow (file analysis, code review, dependency check, testing)
            - Tool usage patterns (how to read files, search code, run commands effectively)
            - Multi-step procedures the agent performed successfully
            - Project conventions or architectural patterns discovered

            ## When NOT to create:
            - The conversation was too short (< 2 tool calls)
            - It was a simple Q&A with no tool usage

            ## Existing Skills (PATCH instead of CREATE if one already covers this):
            %s

            ## Examples:
            Good skill: "maven-dependency-analysis" — Read two pom.xml files, compare dependencies, categorize by scope
            Good skill: "python-test-coverage-review" — Read source + test files, identify untested functions, suggest tests

            ## Response Format:
            ACTION: PATCH
            SKILL_NAME: <exact name of existing skill>
            PATCH_INSTRUCTIONS: <additions>

            ACTION: CREATE
            SKILL_NAME: <kebab-case-name>
            DESCRIPTION: <one-line description>
            CATEGORY: <coding|debugging|testing|tooling|workflow|sre>
            INSTRUCTIONS: <step-by-step reusable instructions>

            NO_SKILL (only if the conversation truly has nothing reusable)

            ## Conversation:
            """;

    private static final String MEMORY_REVIEW_PROMPT =
            """
            Review the coding agent's conversation for user preferences, project constraints,
            or important context that should persist across sessions.

            Focus on: coding style preferences, tool preferences, project-specific rules,
            deployment constraints, testing requirements.

            If a memory is found:
            MEMORY: <concise summary>
            IMPORTANCE: <0.0-1.0>
            TAGS: <comma-separated tags>

            If no memory is warranted:
            NO_MEMORY

            Conversation:
            """;

    private final ModelProvider modelProvider;
    private final String modelName;
    private final int iterationThreshold;
    private final EvolvedSkillStore skillStore;
    private final Duration timeout;

    public KairoEvolutionPolicy(
            ModelProvider modelProvider,
            String modelName,
            int iterationThreshold,
            EvolvedSkillStore skillStore,
            Duration timeout) {
        this.modelProvider = modelProvider;
        this.modelName = modelName;
        this.iterationThreshold = iterationThreshold;
        this.skillStore = skillStore;
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(60);
    }

    @Override
    public Mono<EvolutionOutcome> review(EvolutionContext context) {
        if (context.iterationCount() < iterationThreshold) {
            return Mono.just(EvolutionOutcome.empty());
        }
        LOG.info("Evolution review triggered: {} iterations, reviewing session",
                context.iterationCount());
        return Mono.zip(reviewSkills(context), reviewMemory(context))
                .map(tuple -> mergeOutcomes(tuple.getT1(), tuple.getT2()))
                .timeout(this.timeout)
                .doOnNext(outcome -> LOG.info("Evolution review complete: hasChanges={}",
                        outcome.hasChanges()))
                .onErrorResume(e -> {
                    LOG.error("Evolution review FAILED: {} ({})", e.getMessage(),
                            e.getClass().getSimpleName(), e);
                    return Mono.just(EvolutionOutcome.empty());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<EvolutionOutcome> reviewSkills(EvolutionContext context) {
        String conversationText = formatConversation(context.conversationHistory());
        String existingSkills = context.existingSkills().stream()
                .map(s -> "- " + s.name() + ": " + s.description())
                .collect(Collectors.joining("\n"));
        if (existingSkills.isEmpty()) existingSkills = "(none)";

        String prompt = String.format(SKILL_REVIEW_PROMPT, existingSkills) + conversationText;
        LOG.info("Evolution skill review: sending prompt ({} chars) to model '{}'",
                prompt.length(), modelName);
        return callLlm(prompt)
                .doOnNext(resp -> LOG.info("Evolution skill review response ({} chars): {}",
                        resp.length(), resp.length() > 200 ? resp.substring(0, 200) + "..." : resp))
                .map(this::parseSkillResponse)
                .doOnNext(outcome -> LOG.info("Evolution skill review outcome: hasSkill={}, hasPatch={}",
                        outcome.skillToCreate().isPresent(), outcome.skillToPatch().isPresent()))
                .onErrorResume(e -> {
                    LOG.warn("Evolution skill review FAILED: {} ({})",
                            e.getMessage(), e.getClass().getSimpleName());
                    return Mono.just(EvolutionOutcome.empty());
                });
    }

    private Mono<EvolutionOutcome> reviewMemory(EvolutionContext context) {
        String conversationText = formatConversation(context.conversationHistory());
        LOG.info("Evolution memory review: sending prompt ({} chars)", conversationText.length());
        return callLlm(MEMORY_REVIEW_PROMPT + conversationText)
                .doOnNext(resp -> LOG.info("Evolution memory review response ({} chars): {}",
                        resp.length(), resp.length() > 200 ? resp.substring(0, 200) + "..." : resp))
                .map(this::parseMemoryResponse)
                .onErrorResume(e -> {
                    LOG.warn("Evolution memory review FAILED: {} ({})",
                            e.getMessage(), e.getClass().getSimpleName());
                    return Mono.just(EvolutionOutcome.empty());
                });
    }

    private Mono<String> callLlm(String prompt) {
        LOG.info("Evolution LLM call: model={}, promptLength={}", modelName, prompt.length());
        ModelConfig config = ModelConfig.builder()
                .model(modelName).maxTokens(2048).temperature(0.3).build();
        return modelProvider.call(List.of(Msg.of(MsgRole.USER, prompt)), config)
                .map(response -> response.contents().stream()
                        .filter(Content.TextContent.class::isInstance)
                        .map(c -> ((Content.TextContent) c).text())
                        .findFirst().orElse(""));
    }

    private EvolutionOutcome parseSkillResponse(String response) {
        if (response == null || response.isBlank() || response.trim().equals("NO_SKILL")) {
            return EvolutionOutcome.empty();
        }

        String action = extractField(response, "ACTION:");
        String name = extractField(response, "SKILL_NAME:");

        if ("PATCH".equalsIgnoreCase(action.trim())) {
            String patchInstructions = extractField(response, "PATCH_INSTRUCTIONS:");
            if (name.isEmpty() || patchInstructions.isEmpty()) {
                return EvolutionOutcome.empty();
            }
            Optional<EvolvedSkill> existing = skillStore.get(name).block();
            if (existing.isPresent()) {
                EvolvedSkill patched = new EvolvedSkill(
                        existing.get().name(),
                        bumpVersion(existing.get().version()),
                        existing.get().description(),
                        existing.get().instructions() + "\n\n## Update\n" + patchInstructions,
                        existing.get().category(),
                        existing.get().tags(),
                        SkillTrustLevel.VALIDATED,
                        existing.get().metadata(),
                        existing.get().createdAt(),
                        Instant.now(),
                        existing.get().usageCount());
                LOG.info("Evolution: PATCH existing skill '{}'", name);
                return new EvolutionOutcome(
                        Optional.empty(), Optional.of(patched), List.of(),
                        "Patched skill: " + name);
            }
            LOG.info("Evolution: PATCH target '{}' not found, treating as CREATE", name);
        }

        String description = extractField(response, "DESCRIPTION:");
        String category = extractField(response, "CATEGORY:");
        String instructions = extractField(response, "INSTRUCTIONS:");
        if (instructions.isEmpty()) {
            instructions = extractField(response, "PATCH_INSTRUCTIONS:");
        }

        if (name.isEmpty() || instructions.isEmpty()) {
            LOG.debug("Incomplete skill response, skipping");
            return EvolutionOutcome.empty();
        }

        EvolvedSkill skill = new EvolvedSkill(
                name, "1.0.0",
                description.isEmpty() ? name : description,
                instructions,
                category.isEmpty() ? "coding" : category,
                Set.of(), SkillTrustLevel.VALIDATED, null,
                Instant.now(), Instant.now(), 0);
        LOG.info("Evolution: CREATE new skill '{}'", name);
        return new EvolutionOutcome(
                Optional.of(skill), Optional.empty(), List.of(),
                "Created skill: " + name);
    }

    private EvolutionOutcome parseMemoryResponse(String response) {
        if (response == null || response.isBlank() || response.trim().equals("NO_MEMORY")) {
            return EvolutionOutcome.empty();
        }
        String memory = extractField(response, "MEMORY:");
        if (memory.isEmpty()) return EvolutionOutcome.empty();

        String importanceStr = extractField(response, "IMPORTANCE:");
        double importance = 0.5;
        try {
            if (!importanceStr.isEmpty()) {
                importance = Math.max(0.0, Math.min(1.0, Double.parseDouble(importanceStr.trim())));
            }
        } catch (NumberFormatException ignored) {}

        String tagsStr = extractField(response, "TAGS:");
        Set<String> tags = tagsStr.isEmpty() ? Set.of()
                : Set.of(tagsStr.split(",")).stream()
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet());

        io.kairo.api.memory.MemoryEntry entry = new io.kairo.api.memory.MemoryEntry(
                java.util.UUID.randomUUID().toString(), null,
                memory, memory, io.kairo.api.memory.MemoryScope.SESSION,
                importance, null, tags, Instant.now(), null);
        return new EvolutionOutcome(
                Optional.empty(), Optional.empty(), List.of(entry),
                "Memory extracted");
    }

    private EvolutionOutcome mergeOutcomes(EvolutionOutcome skill, EvolutionOutcome memory) {
        return new EvolutionOutcome(
                skill.skillToCreate().or(memory::skillToCreate),
                skill.skillToPatch().or(memory::skillToPatch),
                memory.memoriesToSave().isEmpty() ? skill.memoriesToSave() : memory.memoriesToSave(),
                String.join("; ",
                        skill.reviewNotes() != null ? skill.reviewNotes() : "",
                        memory.reviewNotes() != null ? memory.reviewNotes() : "").trim());
    }

    private static String extractField(String response, String fieldPrefix) {
        for (String line : response.split("\n")) {
            if (line.startsWith(fieldPrefix)) {
                return line.substring(fieldPrefix.length()).trim();
            }
        }
        return "";
    }

    private static String bumpVersion(String version) {
        if (version == null || version.isEmpty()) return "1.0.1";
        String[] parts = version.split("\\.");
        if (parts.length == 3) {
            try {
                int patch = Integer.parseInt(parts[2]) + 1;
                return parts[0] + "." + parts[1] + "." + patch;
            } catch (NumberFormatException ignored) {}
        }
        return version + ".1";
    }

    private static String formatConversation(List<Msg> history) {
        if (history == null || history.isEmpty()) return "(empty)";
        int maxMsgs = Math.min(history.size(), 30);
        List<Msg> recent = history.subList(history.size() - maxMsgs, history.size());
        StringBuilder sb = new StringBuilder();
        for (Msg msg : recent) {
            String role = msg.role() == MsgRole.USER ? "User" : "Agent";
            String text = msg.text();
            if (text != null && text.length() > 500) {
                text = text.substring(0, 500) + "...";
            }
            if (text != null && !text.isBlank()) {
                sb.append(role).append(": ").append(text).append("\n\n");
            }
        }
        return sb.toString();
    }
}
