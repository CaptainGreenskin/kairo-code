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
package io.kairo.code.core.team.tools;

import io.kairo.api.team.EvaluatorPreference;
import io.kairo.api.team.PlannerFailureMode;
import io.kairo.api.team.RiskProfile;
import io.kairo.api.team.TeamConfig;
import io.kairo.api.team.TeamResourceConstraint;
import io.kairo.api.team.TeamResult;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolParam;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.code.core.team.SwarmCoordinator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Agent-callable tool that launches an expert team execution.
 *
 * <p>Delegates to {@link SwarmCoordinator#startExpertTeam(String, TeamConfig, List)} for the
 * actual plan→generate→evaluate lifecycle and translates the {@link TeamResult} into a
 * JSON-formatted {@link ToolResult}.
 */
@Tool(
    name = "expert_team",
    description = "Launch an expert team to collaboratively solve a complex task. "
        + "Assembles a team of specialized agents (coder, reviewer, architect, etc.) "
        + "that plan, execute, and evaluate work in feedback loops.",
    category = ToolCategory.GENERAL,
    sideEffect = ToolSideEffect.READ_ONLY
)
public class ExpertTeamTool implements SyncTool {

    private final SwarmCoordinator swarmCoordinator;

    ExpertTeamTool() { this.swarmCoordinator = null; }

    public ExpertTeamTool(SwarmCoordinator swarmCoordinator) {
        this.swarmCoordinator = swarmCoordinator;
    }

    @ToolParam(description = "The task description for the expert team.", required = true)
    private String goal;

    @ToolParam(description = "Specific role IDs to use (e.g. [\"expert:coder\", \"expert:reviewer\"]). "
        + "Empty list lets the planner decide.", required = false)
    private List<String> roles;

    @ToolParam(description = "Max concurrent steps (default 4).", required = false)
    private Integer parallel_limit;

    @ToolParam(description = "Cost budget in USD (default unlimited).", required = false)
    private Double budget_usd;

    @ToolParam(description = "Team timeout in minutes (default 10).", required = false)
    private Integer timeout_minutes;

    @ToolParam(description = "Max feedback rounds per step (default 3).", required = false)
    private Integer max_rounds;

    @Override
    public Mono<ToolResult> execute(Map<String, Object> input, ToolContext ctx) {
        String goalIn = stringInput(input, "goal");
        if (goalIn == null || goalIn.isBlank()) {
            return Mono.just(ToolResult.error(null,
                "Parameter 'goal' is required and must be non-blank."));
        }

        List<String> roleIds = listInput(input, "roles");
        TeamConfig config = buildConfig(input);

        return swarmCoordinator.startExpertTeam(goalIn, config, roleIds)
            .map(result -> {
                String output = result.finalOutput()
                    .orElseGet(() -> "Team completed with warnings: "
                        + String.join("; ", result.warnings()));

                String json = "{"
                    + "\"status\": \"" + escape(result.status().name()) + "\""
                    + ", \"output\": \"" + escape(output) + "\""
                    + ", \"stepCount\": " + result.stepOutcomes().size()
                    + ", \"duration\": \"" + result.totalDuration() + "\""
                    + ", \"warnings\": [" + formatWarnings(result.warnings()) + "]"
                    + "}";
                return ToolResult.success(null, json);
            })
            .onErrorResume(ex -> Mono.just(
                ToolResult.error(null, "Expert team execution failed: " + ex.getMessage())));
    }

    private TeamConfig buildConfig(Map<String, Object> input) {
        Integer maxRounds = intInput(input, "max_rounds");
        Integer timeoutMin = intInput(input, "timeout_minutes");
        Integer parallelLimit = intInput(input, "parallel_limit");
        Double budgetUsd = doubleInput(input, "budget_usd");

        if (maxRounds == null && timeoutMin == null && parallelLimit == null && budgetUsd == null) {
            return TeamConfig.defaults();
        }

        int rounds = maxRounds != null ? maxRounds : 3;
        long timeout = timeoutMin != null ? timeoutMin : 10L;

        // Honor parallel_limit and budget_usd via the resource constraint the framework
        // actually enforces. Start from the unbounded ceilings and tighten only what was
        // requested. budget_usd has no native field on TeamResourceConstraint (which is
        // token-based), so convert it to a maxTotalTokens ceiling using the CostBudget
        // pricing model — this is how the USD budget becomes an enforced limit instead of
        // being silently ignored as before.
        TeamResourceConstraint base = TeamResourceConstraint.unbounded();
        long maxTokens = base.maxTotalTokens();
        int maxParallel = parallelLimit != null && parallelLimit > 0
                ? parallelLimit : base.maxParallelSteps();
        if (budgetUsd != null && budgetUsd > 0) {
            // Blended $/token across input+output for an unknown model (DEFAULT_PRICING).
            double costPerTokenPair = new io.kairo.code.core.team.CostPricingConfig()
                    .estimate(1, 1, "");
            double costPerToken = costPerTokenPair / 2.0;
            if (costPerToken > 0) {
                maxTokens = (long) Math.max(1, budgetUsd / costPerToken);
            }
        }

        TeamResourceConstraint constraint = new TeamResourceConstraint(
                maxTokens, Duration.ofMinutes(timeout), maxParallel, rounds);

        return new TeamConfig(
            RiskProfile.MEDIUM,
            rounds,
            Duration.ofMinutes(timeout),
            EvaluatorPreference.AUTO,
            PlannerFailureMode.FAIL_FAST,
            constraint);
    }

    // ── input helpers ──

    private static String stringInput(Map<String, Object> input, String key) {
        Object v = input.get(key);
        return v == null ? null : v.toString();
    }

    private static Integer intInput(Map<String, Object> input, String key) {
        Object v = input.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double doubleInput(Map<String, Object> input, String key) {
        Object v = input.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> listInput(Map<String, Object> input, String key) {
        Object v = input.get(key);
        if (v == null) return List.of();
        if (v instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(item == null ? "" : item.toString());
            }
            return result;
        }
        return List.of();
    }

    // ── output helpers ──

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }

    private static String formatWarnings(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < warnings.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(escape(warnings.get(i))).append("\"");
        }
        return sb.toString();
    }
}
