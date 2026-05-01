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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runtime state of a Swarm run.
 */
public class SwarmExecution {

    private final String teamId;
    private final SwarmConfig config;
    private volatile SwarmPhase currentPhase;
    private final List<PhaseResult> phaseHistory = new CopyOnWriteArrayList<>();
    private volatile SwarmStatus status = SwarmStatus.RUNNING;

    public enum SwarmStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

    public record PhaseResult(
        String phaseName,
        List<String> workerOutputs,
        String synthesis,
        long completedAt
    ) {}

    public SwarmExecution(String teamId, SwarmConfig config) {
        this.teamId = teamId;
        this.config = config;
        this.currentPhase = new SwarmPhase.Research(config.researchPrompts());
    }

    public String teamId() { return teamId; }
    public SwarmConfig config() { return config; }
    public SwarmPhase currentPhase() { return currentPhase; }
    public List<PhaseResult> phaseHistory() { return phaseHistory; }
    public SwarmStatus status() { return status; }

    void advanceTo(SwarmPhase next) { this.currentPhase = next; }
    void recordPhase(PhaseResult result) { phaseHistory.add(result); }
    void complete() { this.status = SwarmStatus.COMPLETED; }
    void fail() { this.status = SwarmStatus.FAILED; }
}
