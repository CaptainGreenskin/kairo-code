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
package io.kairo.code.cli.commands;

import io.kairo.api.team.TeamConfig;
import io.kairo.api.team.TeamResult;
import io.kairo.api.team.TeamResult.StepOutcome;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.core.team.SwarmCoordinator;
import io.kairo.multiagent.subagent.ExpertProfile;
import io.kairo.multiagent.subagent.ExpertRoleRegistry;
import java.io.PrintWriter;
import java.util.List;
import java.util.Set;

/**
 * Launches and manages expert team executions from the REPL.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code :expert <goal>} — start expert team execution</li>
 *   <li>{@code :expert plan <goal>} — generate plan only, wait for confirmation</li>
 *   <li>{@code :expert confirm} — confirm and execute the last plan</li>
 *   <li>{@code :expert roles} — list available expert roles</li>
 *   <li>{@code :expert status} — show last execution status</li>
 * </ul>
 */
public class ExpertCommand implements SlashCommand {

    private static final String RESET;
    private static final String BOLD;
    private static final String DIM;
    private static final String CYAN;
    private static final String GREEN;
    private static final String RED;

    static {
        boolean color = System.getenv("NO_COLOR") == null && System.console() != null;
        RESET = color ? "\033[0m" : "";
        BOLD = color ? "\033[1m" : "";
        DIM = color ? "\033[2m" : "";
        CYAN = color ? "\033[36m" : "";
        GREEN = color ? "\033[32m" : "";
        RED = color ? "\033[31m" : "";
    }

    private volatile TeamResult lastResult;

    @Override
    public String name() {
        return "expert";
    }

    @Override
    public String description() {
        return "Launch expert team (plan / confirm / roles / status)";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        SwarmCoordinator coordinator = context.swarmCoordinator();

        if (coordinator == null) {
            writer.println("Expert team unavailable: no SwarmCoordinator configured.");
            writer.println("Ensure kairo-expert-team is on the classpath.");
            writer.flush();
            return;
        }

        String trimmed = args == null ? "" : args.strip();
        if (trimmed.isEmpty()) {
            printUsage(writer);
            return;
        }

        String[] parts = trimmed.split("\\s+", 2);
        String sub = parts[0].toLowerCase();
        String rest = parts.length > 1 ? parts[1].strip() : "";

        switch (sub) {
            case "plan" -> handlePlan(rest, coordinator, writer);
            case "confirm" -> handleConfirm(coordinator, writer);
            case "roles" -> handleRoles(coordinator, writer);
            case "status" -> handleStatus(writer);
            default -> handleExecute(trimmed, coordinator, writer);
        }
    }

    private void handleExecute(String goal, SwarmCoordinator coordinator, PrintWriter writer) {
        if (goal.isBlank()) {
            writer.println("Usage: :expert <goal>");
            writer.flush();
            return;
        }

        writer.println();
        writer.println(BOLD + "Starting expert team..." + RESET);
        writer.flush();

        try {
            TeamResult result = coordinator
                    .startExpertTeam(goal, TeamConfig.defaults(), List.of())
                    .block();
            this.lastResult = result;
            printResult(result, writer);
        } catch (Exception e) {
            writer.println(RED + "Expert team execution failed: " + e.getMessage() + RESET);
            writer.flush();
        }
    }

    private void handlePlan(String goal, SwarmCoordinator coordinator, PrintWriter writer) {
        if (goal.isBlank()) {
            writer.println("Usage: :expert plan <goal>");
            writer.flush();
            return;
        }

        writer.println();
        writer.println(BOLD + "Generating expert team plan..." + RESET);
        writer.flush();

        try {
            TeamResult result = coordinator
                    .startExpertTeam(goal, TeamConfig.defaults(), List.of(), true)
                    .block();
            this.lastResult = result;

            if (result != null && isPlanReady(result)) {
                writer.println(GREEN + "Plan generated successfully." + RESET);
                printPlanSummary(result, writer);
                writer.println();
                writer.println(
                        "Type " + CYAN + ":expert confirm" + RESET + " to execute the plan.");
            } else {
                printResult(result, writer);
            }
        } catch (Exception e) {
            writer.println(RED + "Plan generation failed: " + e.getMessage() + RESET);
        }
        writer.flush();
    }

    private static boolean isPlanReady(TeamResult result) {
        return result.warnings().stream()
                .anyMatch(w -> w.toLowerCase().contains("awaiting confirmation"));
    }

    private void handleConfirm(SwarmCoordinator coordinator, PrintWriter writer) {
        String teamId = coordinator.lastTeamId();
        if (teamId == null) {
            writer.println("No pending plan to confirm. Use :expert plan <goal> first.");
            writer.flush();
            return;
        }

        writer.println();
        writer.println(BOLD + "Executing confirmed plan..." + RESET);
        writer.flush();

        try {
            TeamResult result = coordinator.confirmAndExecute(teamId).block();
            this.lastResult = result;
            printResult(result, writer);
        } catch (Exception e) {
            writer.println(RED + "Execution failed: " + e.getMessage() + RESET);
            writer.flush();
        }
    }

    private void handleRoles(SwarmCoordinator coordinator, PrintWriter writer) {
        ExpertRoleRegistry registry = coordinator.roleRegistry();
        Set<String> roleIds = registry.registeredRoleIds();

        writer.println();
        writer.println(BOLD + "Available Expert Roles" + RESET);
        writer.println(DIM + "─".repeat(42) + RESET);

        for (String roleId : roleIds) {
            registry.resolve(roleId).ifPresent(profile -> {
                String desc = profileDescription(profile);
                writer.printf("  %-22s %s%n", roleId, desc);
            });
        }
        writer.println();
        writer.flush();
    }

    private void handleStatus(PrintWriter writer) {
        writer.println();
        if (lastResult == null) {
            writer.println("No expert team execution recorded in this session.");
        } else {
            writer.println(BOLD + "Last Expert Team Result" + RESET);
            printResult(lastResult, writer);
        }
        writer.flush();
    }

    private void printResult(TeamResult result, PrintWriter writer) {
        if (result == null) {
            writer.println(DIM + "(no result)" + RESET);
            writer.flush();
            return;
        }

        String statusColor = switch (result.status()) {
            case COMPLETED -> GREEN;
            case FAILED, CANCELLED -> RED;
            default -> DIM;
        };

        writer.println();
        writer.println(BOLD + "Result: " + RESET + statusColor + result.status() + RESET);
        writer.println("  Duration: " + formatDuration(result.totalDuration()));
        writer.println("  Steps:    " + result.stepOutcomes().size());

        for (StepOutcome step : result.stepOutcomes()) {
            String verdict = step.finalVerdict().outcome().name();
            writer.println("    " + step.stepId() + ": " + verdict
                    + (step.attempts() > 1 ? " (" + step.attempts() + " attempts)" : ""));
        }

        result.finalOutput().ifPresent(output -> {
            writer.println();
            writer.println(BOLD + "Output:" + RESET);
            writer.println(output);
        });

        if (!result.warnings().isEmpty()) {
            writer.println();
            writer.println(BOLD + "Warnings:" + RESET);
            for (String w : result.warnings()) {
                writer.println("  - " + w);
            }
        }
        writer.println();
        writer.flush();
    }

    private void printPlanSummary(TeamResult result, PrintWriter writer) {
        writer.println("  Status: " + result.status());
        writer.println("  Steps:  " + result.stepOutcomes().size());
        for (StepOutcome step : result.stepOutcomes()) {
            writer.println("    - " + step.stepId());
        }
    }

    private void printUsage(PrintWriter writer) {
        writer.println("Usage:");
        writer.println("  :expert <goal>          Start expert team execution");
        writer.println("  :expert plan <goal>     Generate plan only (review before executing)");
        writer.println("  :expert confirm         Execute the last generated plan");
        writer.println("  :expert roles           List available expert roles");
        writer.println("  :expert status          Show last execution result");
        writer.flush();
    }

    private static String profileDescription(ExpertProfile profile) {
        if (profile.roleDefinition() != null) {
            String name = profile.roleDefinition().roleName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        if (profile.skillProfile() != null && !profile.skillProfile().isBlank()) {
            return profile.skillProfile();
        }
        return "";
    }

    private static String formatDuration(java.time.Duration d) {
        long secs = d.toSeconds();
        if (secs < 60) {
            return secs + "s";
        }
        return (secs / 60) + "m " + (secs % 60) + "s";
    }
}
