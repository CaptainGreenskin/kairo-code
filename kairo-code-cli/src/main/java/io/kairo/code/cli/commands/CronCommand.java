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

import io.kairo.api.cron.CronScheduler;
import io.kairo.api.cron.CronTask;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import java.io.PrintWriter;
import java.util.List;

/**
 * Manage scheduled cron tasks (kairo-cron SPI). Subcommands:
 *
 * <ul>
 *   <li>{@code :cron list} — show all registered tasks
 *   <li>{@code :cron add <cron-expr> <prompt>} — create a recurring durable task
 *   <li>{@code :cron delete <id>} — remove a task
 *   <li>{@code :cron start} — begin the scheduler tick loop
 *   <li>{@code :cron stop} — halt the scheduler (tasks survive)
 * </ul>
 *
 * <p>Fire callback currently logs to stdout — full agent re-invocation on fire is a
 * follow-on (see M-A4 / cron-agent-bridge).
 */
public class CronCommand implements SlashCommand {

    @Override
    public String name() {
        return "cron";
    }

    @Override
    public String description() {
        return "Manage scheduled cron tasks (list/add/delete/start/stop)";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        CronScheduler scheduler = context.cronScheduler();
        if (scheduler == null) {
            writer.println("Cron scheduler unavailable: not wired in this session.");
            writer.flush();
            return;
        }

        String trimmed = args == null ? "" : args.trim();
        if (trimmed.isEmpty() || trimmed.equals("list")) {
            handleList(scheduler, writer);
            return;
        }
        if (trimmed.equals("start")) {
            scheduler.start();
            writer.println("Cron scheduler started.");
            writer.flush();
            return;
        }
        if (trimmed.equals("stop")) {
            scheduler.stop();
            writer.println("Cron scheduler stopped.");
            writer.flush();
            return;
        }
        if (trimmed.startsWith("delete ")) {
            String id = trimmed.substring(7).trim();
            boolean removed = scheduler.delete(id);
            writer.println(removed ? "Deleted task: " + id : "No task with id: " + id);
            writer.flush();
            return;
        }
        if (trimmed.startsWith("add ")) {
            handleAdd(trimmed.substring(4).trim(), scheduler, writer);
            return;
        }
        printUsage(writer);
    }

    private void handleList(CronScheduler scheduler, PrintWriter writer) {
        List<CronTask> tasks = scheduler.list();
        if (tasks.isEmpty()) {
            writer.println("No scheduled tasks.");
            writer.println("Add one with: :cron add <cron-expr> <prompt>");
            writer.flush();
            return;
        }
        writer.printf("%-12s %-20s %-8s %s%n", "id", "cron", "state", "prompt");
        for (CronTask t : tasks) {
            writer.printf(
                    "%-12s %-20s %-8s %s%n",
                    truncate(t.id(), 12),
                    truncate(t.cron(), 20),
                    t.paused() ? "paused" : "active",
                    truncate(t.prompt(), 60));
        }
        writer.flush();
    }

    private void handleAdd(String rest, CronScheduler scheduler, PrintWriter writer) {
        // Cron expressions can contain whitespace (5 fields). We need to split the FIRST
        // 5 whitespace-separated tokens as the cron expression and the rest as the prompt.
        String[] parts = rest.split("\\s+", 6);
        if (parts.length < 6) {
            writer.println("Usage: :cron add <m> <h> <dom> <mon> <dow> <prompt>");
            writer.println("Example: :cron add 0 9 * * 1-5 review my open PRs");
            writer.flush();
            return;
        }
        String cron = String.join(" ", parts[0], parts[1], parts[2], parts[3], parts[4]);
        String prompt = parts[5];
        try {
            CronTask task = scheduler.create(cron, prompt, true, true);
            writer.println("Created task: " + task.id() + "  (" + cron + ")");
        } catch (Exception e) {
            writer.println("Failed to create task: " + e.getMessage());
        }
        writer.flush();
    }

    private void printUsage(PrintWriter writer) {
        writer.println("Usage:");
        writer.println("  :cron list                          List all scheduled tasks");
        writer.println("  :cron add <m> <h> <dom> <mon> <dow> <prompt>  Create a recurring task");
        writer.println("  :cron delete <id>                   Remove a task");
        writer.println("  :cron start                         Start the scheduler tick loop");
        writer.println("  :cron stop                          Halt the scheduler (tasks persist)");
        writer.flush();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
