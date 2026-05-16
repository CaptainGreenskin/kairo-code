package io.kairo.code.core.skill;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.skill.SkillCategory;
import io.kairo.api.skill.SkillDefinition;
import io.kairo.api.skill.SkillRegistry;
import io.kairo.skill.DefaultSkillRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SkillMatcherTest {

    private SkillRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultSkillRegistry();
        registry.register(new SkillDefinition(
                "git-commit", "1.0", "Create git commits",
                "Instructions for committing", List.of("commit", "git push"),
                SkillCategory.GENERAL));
        registry.register(new SkillDefinition(
                "code-review", "1.0", "Review code for quality",
                "Instructions for reviewing", List.of("review", "code review"),
                SkillCategory.GENERAL));
        registry.register(new SkillDefinition(
                "deploy", "1.0", "Deploy to production",
                "Deploy instructions", List.of("deploy", "release"),
                SkillCategory.GENERAL));
        registry.register(new SkillDefinition(
                "refactor", "1.0", "Refactor code structure",
                "Refactor instructions", null,
                SkillCategory.GENERAL));
    }

    @Test
    void matchesByTriggerCondition() {
        Set<String> matched = SkillMatcher.match(registry, "Please commit these changes");
        assertThat(matched).contains("git-commit");
    }

    @Test
    void matchesByTriggerConditionCaseInsensitive() {
        Set<String> matched = SkillMatcher.match(registry, "Do a CODE REVIEW on this PR");
        assertThat(matched).contains("code-review");
    }

    @Test
    void matchesByKeywordOverlap() {
        Set<String> matched = SkillMatcher.match(registry, "refactor the code structure");
        assertThat(matched).contains("refactor");
    }

    @Test
    void noMatchForUnrelatedTask() {
        Set<String> matched = SkillMatcher.match(registry, "write unit tests for the parser");
        assertThat(matched).doesNotContain("git-commit", "deploy");
    }

    @Test
    void multipleMatchesPossible() {
        Set<String> matched = SkillMatcher.match(registry,
                "commit the code review changes and deploy");
        assertThat(matched).contains("git-commit", "code-review", "deploy");
    }

    @Test
    void emptyTaskReturnsEmpty() {
        assertThat(SkillMatcher.match(registry, "")).isEmpty();
        assertThat(SkillMatcher.match(registry, null)).isEmpty();
    }

    @Test
    void nullRegistryReturnsEmpty() {
        assertThat(SkillMatcher.match(null, "some task")).isEmpty();
    }
}
