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

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Serializable snapshot of a single step's outcome within a team execution.
 *
 * <p>This record captures the parts of {@code TeamResult.StepOutcome} that survive JSON
 * round-tripping, including the evaluation verdict fields flattened into primitive types.
 * Each step writes its own {@code step-{stepId}.json} file — no contention between parallel steps.
 *
 * @param stepId the step this outcome belongs to; non-null, non-blank
 * @param output the last generated artifact text; non-null
 * @param verdictOutcome string name of the verdict (e.g. "PASS", "REVISE"); non-null
 * @param verdictScore numeric score in [0.0, 1.0]
 * @param verdictFeedback human-readable feedback; non-null
 * @param verdictSuggestions actionable suggestions; never null
 * @param attempts total generation attempts; must be >= 1
 * @param completedAt when the step finished; non-null
 */
public record StepOutcomeRecord(
        String stepId,
        String output,
        String verdictOutcome,
        double verdictScore,
        String verdictFeedback,
        List<String> verdictSuggestions,
        int attempts,
        Instant completedAt) {

    public StepOutcomeRecord {
        requireNonBlank(stepId, "stepId");
        Objects.requireNonNull(output, "output must not be null");
        requireNonBlank(verdictOutcome, "verdictOutcome");
        if (Double.isNaN(verdictScore) || verdictScore < 0.0 || verdictScore > 1.0) {
            throw new IllegalArgumentException(
                    "verdictScore must be in [0.0, 1.0], got " + verdictScore);
        }
        Objects.requireNonNull(verdictFeedback, "verdictFeedback must not be null");
        verdictSuggestions =
                verdictSuggestions == null ? List.of() : List.copyOf(verdictSuggestions);
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be >= 1, got " + attempts);
        }
        Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    private static void requireNonBlank(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(paramName + " must not be null or blank");
        }
    }
}
