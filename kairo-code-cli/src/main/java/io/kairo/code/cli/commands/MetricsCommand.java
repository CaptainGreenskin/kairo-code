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

import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.stats.TurnMetricsCollector;
import io.kairo.code.core.stats.TurnMetricsCollector.TurnSnapshot;
import java.io.PrintWriter;
import java.util.List;

/**
 * Displays per-turn metrics: tool call count, success rate, and duration.
 *
 * <p>Usage: {@code /metrics}
 */
public class MetricsCommand implements SlashCommand {

    private static final String SEPARATOR = "\u2500".repeat(42);

    @Override
    public String name() {
        return "metrics";
    }

    @Override
    public String description() {
        return "Show per-turn metrics: tool calls, duration, totals";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        CodeAgentSession session = context.session();
        TurnMetricsCollector collector = session.turnMetricsCollector();

        List<TurnSnapshot> snapshots = collector.turnSnapshots();
        if (snapshots.isEmpty()) {
            writer.println("No metrics yet.");
            writer.flush();
            return;
        }

        writer.println();
        writer.println("Turn Metrics");
        writer.println(SEPARATOR);
        writer.printf("%-6s %6s  %8s  %12s%n", "Turn", "Tools", "Success%", "Duration(ms)");
        writer.println(SEPARATOR);

        for (TurnSnapshot snapshot : snapshots) {
            writer.printf("%-6d %6d  %7.1f%%  %12d%n",
                    snapshot.turnNumber(),
                    snapshot.toolCalls(),
                    snapshot.successRate(),
                    snapshot.durationMillis());
        }

        writer.println(SEPARATOR);
        writer.printf("Totals: %d turns, %d tool calls, avg %dms/turn%n",
                collector.totalTurns(),
                collector.totalToolCalls(),
                collector.avgDurationPerTurnMillis());
        writer.flush();
    }
}
