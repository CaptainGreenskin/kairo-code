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
 *   <li>If goal matches any keyword (case-insensitive substring) → fan-out (return true).</li>
 *   <li>If goal length &gt; 120 chars → fan-out (return true).</li>
 *   <li>If goal length &lt; 10 chars → demote to ReAct (return false).</li>
 *   <li>Otherwise → fan-out (return true). User explicitly chose Experts mode.</li>
 * </ol>
 *
 * <p>Keywords are checked first to ensure CJK (Chinese/Japanese/Korean) text is
 * handled correctly. CJK characters pack more semantics per character, so a
 * character-count threshold alone would incorrectly demote substantive requests.
 *
 * <p>Keywords are configurable via {@code kairo.experts.keywords} property or
 * {@code KAIRO_EXPERTS_KEYWORDS} env variable (comma-separated override).
 */
@Component
public class HeuristicTriageGate implements TriageGate {

    private static final List<String> DEFAULT_KEYWORDS = List.of(
            "plan", "refactor", "重构", "实现", "build", "design",
            "添加", "审查", "review", "test coverage", "迁移", "implement", "create",
            "计划", "优化", "分析", "改进", "测试", "执行", "生成",
            "audit", "quality", "evaluate", "assess", "inspect", "diagnose",
            "debug", "fix", "improve", "security", "performance", "architecture",
            "检查", "评估", "诊断", "修复", "安全", "性能", "架构"
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

        // Check high-signal keywords FIRST (before length thresholds).
        // This ensures substantive CJK requests are recognized regardless of
        // character-count, since Chinese packs more semantics per character.
        String lower = goal.toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }

        // Length-based fallback: only apply if no keywords matched.
        if (goal.length() > 120) {
            return true;
        }

        // Truly trivial messages: greetings, single words, etc.
        // Threshold lowered from 20→10 for CJK compatibility.
        if (goal.length() < 10) {
            return false;
        }

        // User explicitly chose Experts mode — respect that for non-trivial messages.
        return true;
    }
}
