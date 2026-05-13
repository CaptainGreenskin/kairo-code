/*
 * Copyright 2025-2026 the original author or authors.
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
package io.kairo.code.core.team.persistence;

import io.kairo.api.team.TeamStatus;
import java.time.Instant;
import java.util.*;

/**
 * Manifest for a team execution persisted as {@code manifest.json}.
 *
 * <p>Written by a single-threaded actor (bound to {@code Schedulers.single()}) and updated on:
 * team start, step completion, and team end. Contains the DAG of steps, current status, and a set
 * of completed step IDs used for crash recovery.
 *
 * @param teamId unique identifier for this team execution; non-null, non-blank
 * @param goal the user's original goal / request text; non-null
 * @param status current lifecycle status of the team; non-null
 * @param dag the full step DAG serialized as step-ID-keyed entries; never null
 * @param completedStepIds set of step IDs that have finished successfully; never null
 * @param cost snapshot of cost accounting; non-null
 * @param startedAt when the team execution started; non-null
 * @param lastUpdatedAt when the manifest was last written; non-null
 */
public record TeamManifest(
        String teamId,
        String goal,
        TeamStatus status,
        List<StepEntry> dag,
        Set<String> completedStepIds,
        CostSnapshot cost,
        Instant startedAt,
        Instant lastUpdatedAt) {

    public TeamManifest {
        requireNonBlank(teamId, "teamId");
        Objects.requireNonNull(goal, "goal must not be null");
        Objects.requireNonNull(status, "status must not be null");
        dag = dag == null ? List.of() : List.copyOf(dag);
        completedStepIds = completedStepIds == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(completedStepIds));
        Objects.requireNonNull(cost, "cost must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(lastUpdatedAt, "lastUpdatedAt must not be null");
    }

    /**
     * A serializable entry in the step DAG.
     *
     * @param stepId the step identifier; non-null, non-blank
     * @param description human-readable description; non-null
     * @param assignedRoleId the role bound to this step; non-null, non-blank
     * @param dependsOn IDs of steps this one depends on; never null
     * @param stepIndex zero-based position in the plan
     */
    public record StepEntry(
            String stepId,
            String description,
            String assignedRoleId,
            List<String> dependsOn,
            int stepIndex) {

        public StepEntry {
            requireNonBlank(stepId, "stepId");
            Objects.requireNonNull(description, "description must not be null");
            requireNonBlank(assignedRoleId, "assignedRoleId");
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }
    }

    /**
     * Snapshot of cost accounting for the team execution.
     *
     * @param spent amount consumed so far
     * @param budget total allowed budget
     */
    public record CostSnapshot(double spent, double budget) {}

    private static void requireNonBlank(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(paramName + " must not be null or blank");
        }
    }
}
