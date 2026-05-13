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

import io.kairo.api.team.TeamEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Repository for persisting and recovering team execution state.
 *
 * <p>Implementations must guarantee:
 * <ul>
 *   <li><b>Manifest single-writer</b>: only one thread/scheduler writes {@code manifest.json}
 *       at a time.
 *   <li><b>Step isolation</b>: each step outcome is stored in its own file
 *       ({@code step-{stepId}.json}), so parallel steps never contend.
 *   <li><b>Append-only event log</b>: events are appended to {@code events.jsonl} with
 *       monotonically increasing sequence numbers.
 *   <li><b>Crash recovery</b>: {@link #loadIncomplete()} scans for manifests whose status is
 *       not terminal (COMPLETED, FAILED, CANCELLED, TIMEOUT).
 * </ul>
 */
public interface TeamRepository {

    /**
     * Persist the team manifest (metadata, DAG, status, completed steps).
     *
     * @param teamId the team identifier
     * @param manifest the manifest snapshot to persist
     * @return completes when the write is durable
     */
    Mono<Void> saveManifest(String teamId, TeamManifest manifest);

    /**
     * Persist a single step's outcome.
     *
     * @param teamId the team identifier
     * @param stepId the step identifier
     * @param outcome the serializable step outcome
     * @return completes when the write is durable
     */
    Mono<Void> saveStepOutcome(String teamId, String stepId, StepOutcomeRecord outcome);

    /**
     * Append a team event to the event log.
     *
     * @param teamId the team identifier
     * @param event the event to append
     * @return completes when the append is durable
     */
    Mono<Void> appendEvent(String teamId, TeamEvent event);

    /**
     * Load the manifest for a team execution.
     *
     * @param teamId the team identifier
     * @return the manifest, or empty if not found
     */
    Mono<TeamManifest> loadManifest(String teamId);

    /**
     * Load a single step's outcome.
     *
     * @param teamId the team identifier
     * @param stepId the step identifier
     * @return the step outcome, or empty if not found
     */
    Mono<StepOutcomeRecord> loadStepOutcome(String teamId, String stepId);

    /**
     * Load all events for a team in append order.
     *
     * @param teamId the team identifier
     * @return events ordered by sequence number
     */
    Flux<TeamEvent> loadEvents(String teamId);

    /**
     * Scan for team manifests with non-terminal status for crash recovery.
     *
     * @return manifests whose status is not COMPLETED, FAILED, CANCELLED, or TIMEOUT
     */
    Flux<TeamManifest> loadIncomplete();

    /**
     * Delete all persisted state for a team execution.
     *
     * @param teamId the team identifier
     * @return completes when the deletion is finished
     */
    Mono<Void> delete(String teamId);
}
