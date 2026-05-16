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
package io.kairo.code.cli;

import io.kairo.api.team.TeamEvent;
import io.kairo.api.team.TeamEventType;
import java.io.PrintWriter;
import java.util.Map;

/**
 * Renders {@link TeamEvent}s to the terminal during expert team execution.
 *
 * <p>Each event type maps to a concise, color-coded line. Role names are shown in brackets with
 * distinct ANSI colors matching the Web UI's role metadata palette.
 */
public final class ExpertTeamProgressPrinter {

    private static final String RESET;
    private static final String BOLD;
    private static final String DIM;
    private static final String CYAN;
    private static final String GREEN;
    private static final String RED;
    private static final String YELLOW;
    private static final String MAGENTA;
    private static final String BLUE;

    static {
        boolean color = System.getenv("NO_COLOR") == null && System.console() != null;
        RESET = color ? "[0m" : "";
        BOLD = color ? "[1m" : "";
        DIM = color ? "[2m" : "";
        CYAN = color ? "[36m" : "";
        GREEN = color ? "[32m" : "";
        RED = color ? "[31m" : "";
        YELLOW = color ? "[33m" : "";
        MAGENTA = color ? "[35m" : "";
        BLUE = color ? "[34m" : "";
    }

    private static final Map<String, String> ROLE_COLORS = Map.of(
            "architect", CYAN,
            "researcher", BLUE,
            "coder", GREEN,
            "reviewer", YELLOW,
            "tester", MAGENTA,
            "synthesizer", DIM);

    private final PrintWriter writer;

    public ExpertTeamProgressPrinter(PrintWriter writer) {
        this.writer = writer;
    }

    public void onEvent(TeamEvent event) {
        switch (event.type()) {
            case TEAM_STARTED -> printTeamStarted(event);
            case STEP_ASSIGNED -> printStepAssigned(event);
            case STEP_COMPLETED -> printStepCompleted(event);
            case EVALUATION_RESULT -> printEvaluationResult(event);
            case PLAN_READY -> printPlanReady(event);
            case TEAM_COMPLETED -> printTeamCompleted(event);
            case TEAM_FAILED -> printTeamFailed(event);
            case TEAM_TIMEOUT -> printTeamTimeout(event);
            default -> {}
        }
    }

    private void printTeamStarted(TeamEvent event) {
        String goal = attr(event, "goal");
        writer.println();
        writer.println(BOLD + "Expert Team started" + RESET
                + (goal.isEmpty() ? "" : ": " + goal));
        writer.println(DIM + "─".repeat(50) + RESET);
        writer.flush();
    }

    private void printStepAssigned(TeamEvent event) {
        String role = attr(event, "roleId");
        String stepId = attr(event, "stepId");
        String desc = attr(event, "description");
        String color = ROLE_COLORS.getOrDefault(role.toLowerCase(), DIM);
        writer.println("  " + color + "[" + role.toUpperCase() + "]" + RESET
                + " " + stepId + ": generating..."
                + (desc.isEmpty() ? "" : " " + DIM + "(" + desc + ")" + RESET));
        writer.flush();
    }

    private void printStepCompleted(TeamEvent event) {
        String role = attr(event, "roleId");
        String stepId = attr(event, "stepId");
        String color = ROLE_COLORS.getOrDefault(role.toLowerCase(), DIM);
        writer.println("  " + color + "[" + role.toUpperCase() + "]" + RESET
                + " " + stepId + ": " + GREEN + "done" + RESET);
        writer.flush();
    }

    private void printEvaluationResult(TeamEvent event) {
        String role = attr(event, "roleId");
        String stepId = attr(event, "stepId");
        String verdict = attr(event, "verdict");
        String score = attr(event, "score");
        String attempt = attr(event, "attempt");
        String color = ROLE_COLORS.getOrDefault(role.toLowerCase(), DIM);

        String verdictColor = "PASS".equalsIgnoreCase(verdict) ? GREEN : YELLOW;
        String attemptInfo = attempt.isEmpty() ? "" : " (round " + attempt + ")";
        String scoreInfo = score.isEmpty() ? "" : " score=" + score;
        writer.println("  " + color + "[" + role.toUpperCase() + "]" + RESET
                + " " + stepId + ": " + verdictColor + verdict + RESET
                + scoreInfo + attemptInfo);
        writer.flush();
    }

    private void printPlanReady(TeamEvent event) {
        String steps = attr(event, "stepCount");
        writer.println();
        writer.println(BOLD + "Plan ready" + RESET
                + (steps.isEmpty() ? "" : " (" + steps + " steps)"));
        writer.println("Type " + CYAN + ":expert confirm" + RESET + " to execute.");
        writer.flush();
    }

    private void printTeamCompleted(TeamEvent event) {
        String duration = attr(event, "duration");
        String steps = attr(event, "stepCount");
        writer.println(DIM + "─".repeat(50) + RESET);
        writer.println(GREEN + BOLD + "Expert Team completed" + RESET
                + (steps.isEmpty() ? "" : " (" + steps + " steps)")
                + (duration.isEmpty() ? "" : " in " + duration));
        writer.println();
        writer.flush();
    }

    private void printTeamFailed(TeamEvent event) {
        String reason = attr(event, "reason");
        writer.println(DIM + "─".repeat(50) + RESET);
        writer.println(RED + BOLD + "Expert Team failed" + RESET
                + (reason.isEmpty() ? "" : ": " + reason));
        writer.println();
        writer.flush();
    }

    private void printTeamTimeout(TeamEvent event) {
        String duration = attr(event, "duration");
        writer.println(DIM + "─".repeat(50) + RESET);
        writer.println(RED + BOLD + "Expert Team timed out" + RESET
                + (duration.isEmpty() ? "" : " after " + duration));
        writer.println();
        writer.flush();
    }

    private static String attr(TeamEvent event, String key) {
        Object v = event.attributes().get(key);
        return v == null ? "" : v.toString();
    }
}
