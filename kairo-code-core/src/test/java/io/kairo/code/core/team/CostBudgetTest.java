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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CostBudgetTest {

    private CostPricingConfig pricing;

    @BeforeEach
    void setUp() {
        pricing = new CostPricingConfig();
    }

    @Test
    void costRecordingAccumulatesCorrectly() {
        CostBudget budget = new CostBudget(1.0, pricing);

        // Record a small call: 1000 input tokens at $3/1M = $0.003
        budget.recordCost(1000, 0, "claude-sonnet-4-20250514");
        CostBudget.CostSnapshot snap = budget.snapshot();
        assertEquals(0.003, snap.spent(), 0.0001);
    }

    @Test
    void hardStopReturnedWhenBudgetExceeded() {
        // Budget = $0.01; cost of 5000 input = 5000*3/1M = $0.015 > $0.01
        CostBudget budget = new CostBudget(0.01, pricing);
        CostBudget.CostDecision decision = budget.recordCost(5000, 0, "claude-sonnet-4-20250514");
        assertEquals(CostBudget.CostDecision.HARD_STOP, decision);
    }

    @Test
    void userStopReturnedAt80PercentThreshold() {
        // Budget = $0.01; 80% = $0.008
        // 2500 input tokens at $3/1M = $0.0075 → CONTINUE
        // Then 200 more input = $0.0006 → total $0.0081 → USER_STOP
        CostBudget budget = new CostBudget(0.01, pricing);

        CostBudget.CostDecision first = budget.recordCost(2500, 0, "claude-sonnet-4-20250514");
        assertEquals(CostBudget.CostDecision.CONTINUE, first);

        CostBudget.CostDecision second = budget.recordCost(200, 0, "claude-sonnet-4-20250514");
        assertEquals(CostBudget.CostDecision.USER_STOP, second);
    }

    @Test
    void continueReturnedBelowThreshold() {
        CostBudget budget = new CostBudget(10.0, pricing);
        CostBudget.CostDecision decision = budget.recordCost(100, 100, "claude-sonnet-4-20250514");
        assertEquals(CostBudget.CostDecision.CONTINUE, decision);
    }

    @Test
    void snapshotReflectsCurrentState() {
        CostBudget budget = new CostBudget(5.0, pricing);
        CostBudget.CostSnapshot snap = budget.snapshot();
        assertEquals(0.0, snap.spent());
        assertEquals(5.0, snap.budget());
        assertEquals(0.0, snap.percentUsed());

        // After recording: 1000 input * $3/1M = $0.003
        budget.recordCost(1000, 0, "claude-sonnet-4-20250514");
        CostBudget.CostSnapshot after = budget.snapshot();
        assertEquals(0.003, after.spent(), 0.0001);
        assertTrue(after.percentUsed() > 0);
        assertTrue(after.percentUsed() < 1);
    }

    @Test
    void unknownModelUsesDefaultPricing() {
        CostBudget budget = new CostBudget(1.0, pricing);
        // Default: $3 input, $15 output per 1M
        // 1000 input = $0.003, 1000 output = $0.015
        budget.recordCost(1000, 1000, "unknown-model-xyz");
        CostBudget.CostSnapshot snap = budget.snapshot();
        assertEquals(0.018, snap.spent(), 0.0001);
    }

    @Test
    void zeroOrNegativeBudgetRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CostBudget(0, pricing));
        assertThrows(IllegalArgumentException.class, () -> new CostBudget(-5.0, pricing));
    }
}
