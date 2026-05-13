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

import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks cost spending for an expert team execution.
 * Triggers approval at 80% budget and hard-stops at 100%.
 */
public class CostBudget {

    private final double budgetUsd;
    private final AtomicReference<Double> spent = new AtomicReference<>(0.0);
    private final CostPricingConfig pricing;

    public enum CostDecision { CONTINUE, USER_STOP, HARD_STOP }

    public CostBudget(double budgetUsd, CostPricingConfig pricing) {
        if (budgetUsd <= 0) throw new IllegalArgumentException("budgetUsd must be positive");
        this.budgetUsd = budgetUsd;
        this.pricing = pricing;
    }

    /**
     * Record cost from an LLM call. Returns decision based on budget state.
     * The approval integration (80% threshold) is handled by the caller
     * since UserApprovalHandler is session-scoped in kairo-code.
     */
    public CostDecision recordCost(int inputTokens, int outputTokens, String model) {
        double cost = pricing.estimate(inputTokens, outputTokens, model);
        double total = spent.updateAndGet(v -> v + cost);

        if (total >= budgetUsd) {
            return CostDecision.HARD_STOP;
        } else if (total >= budgetUsd * 0.8) {
            return CostDecision.USER_STOP;  // caller should request approval
        }
        return CostDecision.CONTINUE;
    }

    public CostSnapshot snapshot() {
        return new CostSnapshot(spent.get(), budgetUsd);
    }

    public record CostSnapshot(double spent, double budget) {
        public double percentUsed() { return budget > 0 ? spent / budget : 0; }
    }
}
