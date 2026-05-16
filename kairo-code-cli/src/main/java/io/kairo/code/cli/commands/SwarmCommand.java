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
import io.kairo.code.core.team.SwarmCoordinator;
import java.io.PrintWriter;
import java.util.Set;

/**
 * Displays Swarm execution status. When a {@link SwarmCoordinator} is available on the {@link
 * ReplContext}, shows the registered roles and last team ID. Otherwise, falls back to guidance to
 * use the Web UI.
 *
 * <p>Usage: {@code :swarm}
 */
public class SwarmCommand implements SlashCommand {

    private static final String SEPARATOR = "─".repeat(42);

    @Override
    public String name() {
        return "swarm";
    }

    @Override
    public String description() {
        return "Show swarm execution status";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        SwarmCoordinator coordinator = context.swarmCoordinator();

        writer.println();
        writer.println("Swarm Status");
        writer.println(SEPARATOR);

        if (coordinator == null) {
            writer.println("No SwarmCoordinator available.");
            writer.println(
                    "Use Web UI → Team → Launch Swarm, or ensure kairo-expert-team");
            writer.println("is on the classpath.");
        } else {
            Set<String> roles = coordinator.roleRegistry().registeredRoleIds();
            String lastTeam = coordinator.lastTeamId();
            writer.println("Coordinator: ready");
            writer.println("Roles:       " + roles.size() + " registered");
            writer.println("Last team:   " + (lastTeam != null ? lastTeam : "(none)"));
            writer.println();
            writer.println("Use :expert <goal> to start an expert team execution.");
        }

        writer.println();
        writer.flush();
    }
}
