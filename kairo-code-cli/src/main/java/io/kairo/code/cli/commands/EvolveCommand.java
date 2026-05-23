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
import io.kairo.code.cli.demo.SelfEvolutionDemo;
import io.kairo.evolution.curator.LifecycleCuratorDaemon;
import io.kairo.evolution.curator.LifecycleTransitionResult;
import java.io.PrintWriter;

/**
 * Self-evolution control. Two surfaces:
 *
 * <ul>
 *   <li>{@code :evolve} — run the legacy SelfEvolutionDemo (kairo-code's existing flow that
 *       inspects the skill registry for gaps and prints a stub of how an evolved skill would
 *       look). Kept for parity until full curator-driven evolution lands.
 *   <li>{@code :evolve curator [start|stop|status|run]} — manage the upstream
 *       {@link LifecycleCuratorDaemon} (kairo-evolution): non-destructive ACTIVE → STALE →
 *       ARCHIVED transitions for skills based on usage telemetry.
 * </ul>
 */
public class EvolveCommand implements SlashCommand {

    @Override
    public String name() {
        return "evolve";
    }

    @Override
    public String description() {
        return "Self-evolution control (curator [start|stop|status|run])";
    }

    @Override
    public void execute(String args, ReplContext context) {
        String trimmed = args == null ? "" : args.trim();
        if (trimmed.isEmpty()) {
            SelfEvolutionDemo.run(context);
            return;
        }
        String[] parts = trimmed.split("\\s+", 2);
        if (!"curator".equals(parts[0])) {
            context.writer().println("Usage: :evolve | :evolve curator [start|stop|status|run]");
            context.writer().flush();
            return;
        }
        String action = parts.length > 1 ? parts[1].trim() : "status";
        handleCurator(action, context);
    }

    private void handleCurator(String action, ReplContext context) {
        PrintWriter writer = context.writer();
        LifecycleCuratorDaemon daemon = context.curatorDaemon();
        if (daemon == null) {
            writer.println("Curator unavailable: not wired in this session.");
            writer.flush();
            return;
        }
        switch (action) {
            case "start" -> {
                daemon.start();
                writer.println("Curator started (interval=" + daemon.config().reviewInterval()
                        + ", stale=" + daemon.config().staleAfter()
                        + ", archive=" + daemon.config().archiveAfter() + ")");
            }
            case "stop" -> {
                daemon.stop();
                writer.println("Curator stopped.");
            }
            case "run" -> {
                LifecycleTransitionResult r = daemon.runOnce(true);
                writer.println(
                        "Ran one curator pass: archived=" + r.archived().size()
                                + " stale=" + r.markedStale().size()
                                + " reactivated=" + r.reactivated().size());
            }
            case "status" -> {
                writer.println("Curator: " + (daemon.isRunning() ? "running" : "stopped"));
                writer.println("Last run: "
                        + (daemon.lastRunAt() != null ? daemon.lastRunAt() : "(never)"));
                LifecycleTransitionResult last = daemon.lastResult();
                if (last != null) {
                    writer.println("Last result: archived=" + last.archived().size()
                            + " stale=" + last.markedStale().size()
                            + " reactivated=" + last.reactivated().size());
                }
            }
            default -> writer.println("Usage: :evolve curator [start|stop|status|run]");
        }
        writer.flush();
    }
}
