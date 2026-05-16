package io.kairo.code.core.skill;

import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Matches skills to a task description using trigger conditions and keyword overlap.
 *
 * <p>Used by child session spawning to pre-load only relevant skills instead of the full parent
 * skill set.
 */
public final class SkillMatcher {

    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[\\s\\p{Punct}]+");

    private SkillMatcher() {}

    /**
     * Select skills relevant to the given task description.
     *
     * <p>A skill matches if any of its triggerConditions appears as a substring in the task
     * description (case-insensitive), or if the keyword overlap score between the task and the
     * skill's name + description exceeds the threshold.
     *
     * @return skill names that match, preserving registry order
     */
    public static Set<String> match(SkillRegistry registry, String taskDescription) {
        if (registry == null || taskDescription == null || taskDescription.isBlank()) {
            return Set.of();
        }

        String lowerTask = taskDescription.toLowerCase(Locale.ROOT);
        Set<String> taskTokens = tokenize(lowerTask);
        Set<String> matched = new LinkedHashSet<>();

        for (SkillDefinition skill : registry.list()) {
            if (matchesTrigger(skill, lowerTask) || matchesKeywords(skill, taskTokens)) {
                matched.add(skill.name());
            }
        }
        return matched;
    }

    private static boolean matchesTrigger(SkillDefinition skill, String lowerTask) {
        List<String> triggers = skill.triggerConditions();
        if (triggers == null || triggers.isEmpty()) {
            return false;
        }
        for (String trigger : triggers) {
            if (lowerTask.contains(trigger.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesKeywords(SkillDefinition skill, Set<String> taskTokens) {
        Set<String> skillTokens = new LinkedHashSet<>();
        skillTokens.addAll(tokenize(skill.name().toLowerCase(Locale.ROOT)));
        if (skill.description() != null) {
            skillTokens.addAll(tokenize(skill.description().toLowerCase(Locale.ROOT)));
        }
        if (skillTokens.isEmpty() || taskTokens.isEmpty()) {
            return false;
        }
        long overlap = skillTokens.stream().filter(taskTokens::contains).count();
        double score = (double) overlap / Math.min(skillTokens.size(), taskTokens.size());
        return score >= 0.3;
    }

    private static Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : TOKEN_SPLITTER.split(text)) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
