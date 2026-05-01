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

public record SwarmConfig(
    String goal,
    int researchWorkers,
    int implementWorkers,
    int verifyWorkers,
    boolean allowParallelImpl,
    int phaseTimeoutSeconds,
    List<String> researchPrompts,
    List<String> implAssignments
) {
    public static SwarmConfig defaults(String goal) {
        return new SwarmConfig(goal, 2, 3, 2, true, 300, List.of(), List.of());
    }
}
