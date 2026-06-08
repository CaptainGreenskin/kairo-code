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

import io.kairo.api.cost.UsageSummary;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.cost.CostEstimator;
import io.kairo.code.core.stats.TurnMetricsCollector;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Displays a session-level summary: duration, turns, tool calls, token estimate, and cost.
 *
 * <p>Usage: {@code /session}
 */
public class SessionCommand implements SlashCommand {

    private static final String SEPARATOR = "─".repeat(42);
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    public String name() {
        return "session";
    }

    @Override
    public String description() {
        return "Show session summary: duration, turns, token estimate, cost";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        CodeAgentSession session = context.session();
        TurnMetricsCollector metrics = session.turnMetricsCollector();
        int totalTurns = metrics.totalTurns();
        int totalToolCalls = metrics.totalToolCalls();

        writer.println();
        writer.println("Session Summary");
        writer.println(SEPARATOR);

        Instant start = context.sessionStartTime();
        Duration elapsed = Duration.between(start, Instant.now());
        writer.printf("Started:    %s%n", TIME_FMT.format(start));
        writer.printf("Duration:   %s%n", formatDuration(elapsed));

        if (totalTurns == 0) {
            writer.println("Turns:      0");
            writer.println("Tool calls: 0");
            writer.println(SEPARATOR);
            writer.println("No turns recorded yet.");
            writer.flush();
            return;
        }

        writer.printf("Turns:      %d%n", totalTurns);
        writer.printf(
                "Tool calls: %d  (avg %.1f/turn)%n", totalToolCalls, metrics.avgToolCallsPerTurn());
        writer.println(SEPARATOR);

        UsageSummary summary = session.costTracker().summary();

        if (summary.totalTokens() > 0) {
            writer.printf("Total tokens:         %,9d%n", summary.totalTokens());
            if (summary.estimatedCostUsd() > 0) {
                writer.printf(
                        "Est. cost:            %11s%n",
                        CostEstimator.format(summary.estimatedCostUsd()));
            } else {
                writer.printf("Est. cost:            %11s%n", "—");
            }
        } else {
            writer.printf("Total tokens:         %11s%n", "—");
            writer.printf("Est. cost:            %11s%n", "—");
        }

        writer.flush();
    }

    private static String formatDuration(Duration d) {
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
}
