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
import io.kairo.expertteam.role.ExpertRoleRegistry;
import java.io.PrintWriter;
import java.util.Set;

/**
 * Displays team status when a {@link SwarmCoordinator} is exposed by the REPL context.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code :team} — show coordinator status and available roles</li>
 *   <li>{@code :team roles} — list all registered expert roles</li>
 * </ul>
 */
public class TeamCommand implements SlashCommand {

    private static final String SEPARATOR = "─".repeat(42);

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
        SwarmCoordinator coordinator = context.swarmCoordinator();
        String trimmed = args != null ? args.trim() : "";

        writer.println();
        writer.println("Team Status");
        writer.println(SEPARATOR);

        if (coordinator == null) {
            writer.println("No team API available in this session.");
            writer.println("Ensure kairo-expert-team is on the classpath, or use the Web UI");
            writer.println("Team panel to manage teams.");
        } else if ("roles".equalsIgnoreCase(trimmed)) {
            ExpertRoleRegistry registry = coordinator.roleRegistry();
            Set<String> roleIds = registry.registeredRoleIds();
            writer.println("Registered roles (" + roleIds.size() + "):");
            for (String roleId : roleIds) {
                registry.resolve(roleId).ifPresent(profile -> {
                    String name = profile.roleDefinition() != null
                            ? profile.roleDefinition().roleName()
                            : roleId;
                    writer.println("  " + roleId + " — " + name);
                });
            }
        } else {
            String lastTeam = coordinator.lastTeamId();
            writer.println("Coordinator:   ready");
            writer.println("Last team:     " + (lastTeam != null ? lastTeam : "(none)"));
            writer.println();
            writer.println("Commands:");
            writer.println("  :team roles      List registered expert roles");
            writer.println("  :expert <goal>   Start expert team execution");
        }

        writer.println();
        writer.flush();
    }
}
