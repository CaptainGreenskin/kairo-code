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
import io.kairo.code.cli.TokenStatusLine;
import io.kairo.code.core.CodeAgentSession;
import io.kairo.code.core.stats.TurnMetricsCollector;
import java.io.PrintWriter;

/**
 * Reports current context window usage and compaction status.
 *
 * <p>Usage: {@code :ctx}
 */
public class CtxCommand implements SlashCommand {

    private static final String SEPARATOR = "\u2500".repeat(42);

    @Override
    public String name() {
        return "ctx";
    }

    @Override
    public String description() {
        return "Show context window usage and compaction status";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        CodeAgentSession session = context.session();

        int contextLimit = TokenStatusLine.contextLimitForModel(context.modelName());
        TurnMetricsCollector collector = session != null ? session.turnMetricsCollector() : null;
        int totalTurns = collector != null ? collector.totalTurns() : 0;
        int totalToolCalls = collector != null ? collector.totalToolCalls() : 0;
        long avgDuration = collector != null ? collector.avgDurationPerTurnMillis() : 0;

        int loadedSkills = context.loadedSkills().size();

        writer.println();
        writer.println("Context Window");
        writer.println(SEPARATOR);
        writer.printf("Model:    %s%n", context.modelName());
        writer.printf("Budget:   %,d tokens%n", contextLimit);

        if (totalTurns == 0) {
            writer.println("Used:     0 tokens  [░░░░░░░░░░░░░░░░░░] 0%");
            writer.println("Phase:    NORMAL  (no turns yet)");
        } else {
            // Estimate tokens from turn data — rough chars/4 heuristic
            // Since we don't have direct token counts from ReplContext,
            // show session activity metrics instead
            writer.printf("Turns:    %d%n", totalTurns);
            writer.printf("Tools:    %d total calls, avg %dms/turn%n", totalToolCalls, avgDuration);
            int percent = Math.min(100, estimateUsagePercent(totalTurns, contextLimit));
            writer.printf("Usage:    %d%%  [%s]%n", percent, progressBar(percent));

            if (percent >= 90) {
                writer.println("Phase:    CRITICAL");
                writer.println();
                writer.println("\u26A0 Warning: context approaching limit \u2014 consider :compact");
            } else if (percent >= 80) {
                writer.println("Phase:    WARNING");
                writer.println();
                writer.println("\u26A0 Consider :compact to compress history");
            } else {
                writer.println("Phase:    NORMAL  (no compaction needed)");
            }
        }

        writer.printf("Skills:   %d loaded%n", loadedSkills);
        writer.println();
        writer.flush();
    }

    /**
     * Rough usage estimate: each turn ~500 tokens average message + response.
     * This is a heuristic since ReplContext doesn't expose raw token counts.
     */
    static int estimateUsagePercent(int totalTurns, int contextLimit) {
        if (contextLimit <= 0) return 0;
        long estimated = (long) totalTurns * 500;
        return (int) ((estimated * 100) / contextLimit);
    }

    /** Builds a 20-character progress bar for the given percentage (0-100). */
    static String progressBar(int percent) {
        int filled = Math.max(0, Math.min(20, percent / 5));
        int empty = 20 - filled;
        return "\u2588".repeat(filled) + "\u2591".repeat(empty);
    }
}
