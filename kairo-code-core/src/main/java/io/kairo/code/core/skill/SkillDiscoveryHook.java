package io.kairo.code.core.skill;

import io.kairo.api.hook.HookHandler;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.PreReasoningEvent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.skill.SkillSearchIndex;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PRE_REASONING hook that auto-discovers relevant skills from user input using TF-IDF search
 * and injects their instructions into the conversation as a system message.
 *
 * <p>Only triggers when skills score above {@link SkillSearchIndex#AUTO_LOAD_THRESHOLD}.
 * Already-injected skills are tracked to avoid duplicate injection across turns.
 */
public class SkillDiscoveryHook {

    private static final Logger log = LoggerFactory.getLogger(SkillDiscoveryHook.class);

    private final SkillSearchIndex searchIndex;
    private final SkillRegistry registry;
    private final Set<String> alreadyLoadedSkills;
    private final Set<String> injectedSkills = new HashSet<>();
    private final BiConsumer<List<String>, List<Double>> onActivated;

    public SkillDiscoveryHook(SkillSearchIndex searchIndex, SkillRegistry registry,
                              Set<String> alreadyLoadedSkills) {
        this(searchIndex, registry, alreadyLoadedSkills, null);
    }

    public SkillDiscoveryHook(SkillSearchIndex searchIndex, SkillRegistry registry,
                              Set<String> alreadyLoadedSkills,
                              BiConsumer<List<String>, List<Double>> onActivated) {
        this.searchIndex = searchIndex;
        this.registry = registry;
        this.alreadyLoadedSkills = alreadyLoadedSkills;
        this.onActivated = onActivated;
    }

    @HookHandler(HookPhase.PRE_REASONING)
    public HookResult<PreReasoningEvent> onPreReasoning(PreReasoningEvent event) {
        String userInput = extractLastUserInput(event.messages());
        if (userInput == null || userInput.isBlank()) {
            return HookResult.proceed(event);
        }

        List<SkillSearchIndex.SearchResult> results = searchIndex.search(userInput, 3);
        List<SkillDefinition> toInject = new ArrayList<>();
        List<String> activatedNames = new ArrayList<>();
        List<Double> activatedScores = new ArrayList<>();

        for (SkillSearchIndex.SearchResult result : results) {
            if (result.score() < SkillSearchIndex.AUTO_LOAD_THRESHOLD) continue;
            if (alreadyLoadedSkills.contains(result.name())) continue;
            if (injectedSkills.contains(result.name())) continue;

            registry.get(result.name()).ifPresent(skill -> {
                if (skill.hasInstructions()) {
                    toInject.add(skill);
                    injectedSkills.add(result.name());
                    activatedNames.add(result.name());
                    activatedScores.add(result.score());
                    log.info("Skill discovery: auto-injecting '{}' (score={})",
                            result.name(), String.format("%.2f", result.score()));
                }
            });
        }

        if (toInject.isEmpty()) {
            return HookResult.proceed(event);
        }

        if (onActivated != null) {
            onActivated.accept(activatedNames, activatedScores);
        }

        StringBuilder section = new StringBuilder();
        section.append("\n\n<skill-discovery>\n");
        for (SkillDefinition skill : toInject) {
            section.append("## ").append(skill.name()).append("\n");
            section.append(skill.instructions()).append("\n\n");
        }
        section.append("</skill-discovery>");

        List<Msg> modified = new ArrayList<>(event.messages());
        modified.add(0, Msg.of(MsgRole.SYSTEM, section.toString()));

        return HookResult.modify(new PreReasoningEvent(modified, event.config(), event.cancelled()),
                null);
    }

    /**
     * Marker object placed in the hooks list so CodeAgentFactory can wire the
     * skill activation callback without changing SessionOptions.
     */
    public record ActivationCallback(BiConsumer<List<String>, List<Double>> callback) {}

    private static String extractLastUserInput(List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.role() == MsgRole.USER) {
                return msg.text();
            }
        }
        return null;
    }
}
