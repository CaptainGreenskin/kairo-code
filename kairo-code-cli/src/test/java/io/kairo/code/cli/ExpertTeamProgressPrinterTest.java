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

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.team.TeamEvent;
import io.kairo.api.team.TeamEventType;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExpertTeamProgressPrinterTest {

    private StringWriter outputCapture;
    private PrintWriter writer;
    private ExpertTeamProgressPrinter printer;

    @BeforeEach
    void setUp() {
        outputCapture = new StringWriter();
        writer = new PrintWriter(outputCapture, true);
        printer = new ExpertTeamProgressPrinter(writer);
    }

    @Test
    void teamStarted_printsGoal() {
        TeamEvent event = event(TeamEventType.TEAM_STARTED, Map.of("goal", "Build REST API"));
        printer.onEvent(event);
        String out = outputCapture.toString();
        assertThat(out).contains("Expert Team started");
        assertThat(out).contains("Build REST API");
    }

    @Test
    void stepAssigned_printsRoleAndStep() {
        TeamEvent event = event(
                TeamEventType.STEP_ASSIGNED,
                Map.of("roleId", "coder", "stepId", "step-1", "description", "Implement endpoint"));
        printer.onEvent(event);
        String out = outputCapture.toString();
        assertThat(out).contains("CODER");
        assertThat(out).contains("step-1");
        assertThat(out).contains("generating");
    }

    @Test
    void stepCompleted_printsDone() {
        TeamEvent event =
                event(TeamEventType.STEP_COMPLETED, Map.of("roleId", "coder", "stepId", "step-1"));
        printer.onEvent(event);
        String out = outputCapture.toString();
        assertThat(out).contains("CODER");
        assertThat(out).contains("step-1");
        assertThat(out).contains("done");
    }

    @Test
    void evaluationResult_printsVerdict() {
        TeamEvent event = event(
                TeamEventType.EVALUATION_RESULT,
                Map.of(
                        "roleId", "reviewer",
                        "stepId", "step-2",
                        "verdict", "PASS",
                        "score", "0.95",
                        "attempt", "1"));
        printer.onEvent(event);
        String out = outputCapture.toString();
        assertThat(out).contains("REVIEWER");
        assertThat(out).contains("PASS");
        assertThat(out).contains("score=0.95");
    }

    @Test
    void teamCompleted_printsSummary() {
        TeamEvent event = event(
                TeamEventType.TEAM_COMPLETED,
                Map.of("duration", "42s", "stepCount", "3"));
        printer.onEvent(event);
        String out = outputCapture.toString();
        assertThat(out).contains("Expert Team completed");
        assertThat(out).contains("3 steps");
        assertThat(out).contains("42s");
    }

    @Test
    void teamFailed_printsReason() {
        TeamEvent event =
                event(TeamEventType.TEAM_FAILED, Map.of("reason", "LLM provider unavailable"));
        printer.onEvent(event);
        String out = outputCapture.toString();
        assertThat(out).contains("Expert Team failed");
        assertThat(out).contains("LLM provider unavailable");
    }

    @Test
    void teamTimeout_printsDuration() {
        TeamEvent event = event(TeamEventType.TEAM_TIMEOUT, Map.of("duration", "10m"));
        printer.onEvent(event);
        String out = outputCapture.toString();
        assertThat(out).contains("Expert Team timed out");
        assertThat(out).contains("10m");
    }

    @Test
    void planReady_printsStepCount() {
        TeamEvent event = event(TeamEventType.PLAN_READY, Map.of("stepCount", "5"));
        printer.onEvent(event);
        String out = outputCapture.toString();
        assertThat(out).contains("Plan ready");
        assertThat(out).contains("5 steps");
    }

    @Test
    void unknownEventType_ignored() {
        TeamEvent event = event(TeamEventType.STEP_THINKING, Map.of());
        printer.onEvent(event);
        assertThat(outputCapture.toString()).isEmpty();
    }

    private static TeamEvent event(TeamEventType type, Map<String, Object> attrs) {
        return new TeamEvent(type, "team-1", "req-1", Instant.now(), attrs);
    }
}
