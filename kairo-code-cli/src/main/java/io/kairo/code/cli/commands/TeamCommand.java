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
import java.io.PrintWriter;

/**
 * Displays team / swarm status when exposed by the REPL context.
 *
 * <p>Usage: {@code :team} &mdash; today {@link ReplContext} does not surface {@code Team} or
 * {@code MessageBus}; the Web UI Team panel is the supported place to manage teams.
 *
 * <p>Usage: {@code :team create <name>} &mdash; reserved; creation is not wired in the REPL yet.
 */
public class TeamCommand implements SlashCommand {

    private static final String SEPARATOR = "\u2500".repeat(42);

    @Override
    public String name() {
        return "team";
    }

    @Override
    public String description() {
        return "Show current team status";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        String trimmed = args != null ? args.trim() : "";

        writer.println();
        writer.println("Team Status");
        writer.println(SEPARATOR);

        if (trimmed.regionMatches(true, 0, "create", 0, 6)
                && (trimmed.length() == 6 || Character.isWhitespace(trimmed.charAt(6)))) {
            writer.println("Creating a team from the REPL is not wired yet (no team API on ReplContext).");
            writer.println("Use the Web UI (Team panel) to create teams and manage members.");
        } else {
            writer.println("No team state is visible from this REPL session.");
            writer.println("No team API is available in ReplContext — use the Web UI Team panel to");
            writer.println("view and manage teams, members, tasks, and messages.");
        }

        writer.println();
        writer.flush();
    }
}
