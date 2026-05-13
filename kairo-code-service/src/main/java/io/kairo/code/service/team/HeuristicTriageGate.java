package io.kairo.code.service.team;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Heuristic-based triage gate that decides whether to fan-out to expert team.
 *
 * <p>Rules (evaluated in order):
 * <ol>
 *   <li>If goal length &lt; 20 chars → demote to ReAct (return false) regardless of keywords.</li>
 *   <li>If goal length &gt; 120 chars → fan-out (return true).</li>
 *   <li>If goal matches any keyword (case-insensitive substring) → fan-out (return true).</li>
 *   <li>Otherwise → demote to ReAct (return false).</li>
 * </ol>
 *
 * <p>Keywords are configurable via {@code kairo.experts.keywords} property or
 * {@code KAIRO_EXPERTS_KEYWORDS} env variable (comma-separated override).
 */
@Component
public class HeuristicTriageGate implements TriageGate {

    private static final List<String> DEFAULT_KEYWORDS = List.of(
            "plan", "refactor", "重构", "实现", "build", "design",
            "添加", "审查", "review", "test coverage", "迁移", "implement", "create"
    );

    private final List<String> keywords;

    public HeuristicTriageGate(
            @Value("${kairo.experts.keywords:}") String keywordsOverride) {
        if (keywordsOverride != null && !keywordsOverride.isBlank()) {
            this.keywords = Arrays.stream(keywordsOverride.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .collect(Collectors.toUnmodifiableList());
        } else {
            this.keywords = DEFAULT_KEYWORDS.stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toUnmodifiableList());
        }
    }

    @Override
    public boolean shouldFanOut(String goal) {
        if (goal == null || goal.isBlank()) {
            return false;
        }

        // Short goals are always simple enough for ReAct
        if (goal.length() < 20) {
            return false;
        }

        // Long goals are complex enough for expert team
        if (goal.length() > 120) {
            return true;
        }

        // Check for complexity keywords (case-insensitive)
        String lower = goal.toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }

        return false;
    }
}
