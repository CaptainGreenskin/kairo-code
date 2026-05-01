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
package io.kairo.code.core.team;

import java.util.List;

/**
 * Swarm execution phases. Sealed interface pattern — each phase defines its allowed roles.
 */
public sealed interface SwarmPhase permits
    SwarmPhase.Research,
    SwarmPhase.Synthesis,
    SwarmPhase.Implementation,
    SwarmPhase.Verification {

    List<TeamRole> allowedRoles();
    boolean isReadOnly();

    /** Phase 1: Explore and gather information — read-only workers */
    record Research(List<String> explorationGoals) implements SwarmPhase {
        @Override public List<TeamRole> allowedRoles() { return List.of(TeamRole.RESEARCHER); }
        @Override public boolean isReadOnly() { return true; }
    }

    /** Phase 2: Synthesize findings into a plan — single coordinator */
    record Synthesis(String researchSummary) implements SwarmPhase {
        @Override public List<TeamRole> allowedRoles() { return List.of(TeamRole.SYNTHESIZER, TeamRole.COORDINATOR); }
        @Override public boolean isReadOnly() { return true; }
    }

    /** Phase 3: Execute the plan — parallel write workers */
    record Implementation(String plan, List<String> assignments) implements SwarmPhase {
        @Override public List<TeamRole> allowedRoles() {
            return List.of(TeamRole.IMPLEMENTER, TeamRole.COORDINATOR);
        }
        @Override public boolean isReadOnly() { return false; }
    }

    /** Phase 4: Adversarial verification — read-only testers */
    record Verification(List<String> verificationCriteria) implements SwarmPhase {
        @Override public List<TeamRole> allowedRoles() { return List.of(TeamRole.VERIFIER); }
        @Override public boolean isReadOnly() { return true; }
    }
}
