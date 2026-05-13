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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CostPricingConfigTest {

    @Test
    void knownModelUsesCorrectPricing() {
        CostPricingConfig config = new CostPricingConfig();
        // claude-sonnet-4: $3 input, $15 output per 1M
        // 1M input + 0 output = $3.0
        double cost = config.estimate(1_000_000, 0, "claude-sonnet-4-20250514");
        assertEquals(3.0, cost, 0.001);

        // 0 input + 1M output = $15.0
        double outputCost = config.estimate(0, 1_000_000, "claude-sonnet-4-20250514");
        assertEquals(15.0, outputCost, 0.001);
    }

    @Test
    void unknownModelUsesDefaultPricing() {
        CostPricingConfig config = new CostPricingConfig();
        // Default: $3 input, $15 output per 1M (same as sonnet)
        double cost = config.estimate(1_000_000, 1_000_000, "totally-unknown-model");
        assertEquals(18.0, cost, 0.001);
    }

    @Test
    void customPricingCanBeAdded() {
        CostPricingConfig config = new CostPricingConfig();
        config.setModelPricing("my-model", new CostPricingConfig.ModelPricing(1.0, 2.0));

        double cost = config.estimate(1_000_000, 1_000_000, "my-model");
        assertEquals(3.0, cost, 0.001);
    }

    @Test
    void estimationFormulaCorrect() {
        // Use a simple config with known values
        CostPricingConfig config = new CostPricingConfig(
            Map.of("test-model", new CostPricingConfig.ModelPricing(10.0, 20.0))
        );

        // 500_000 input at $10/1M = $5.0
        // 250_000 output at $20/1M = $5.0
        // Total = $10.0
        double cost = config.estimate(500_000, 250_000, "test-model");
        assertEquals(10.0, cost, 0.001);
    }

    @Test
    void haikuModelHasLowerPricing() {
        CostPricingConfig config = new CostPricingConfig();
        double sonnetCost = config.estimate(1_000_000, 1_000_000, "claude-sonnet-4-20250514");
        double haikuCost = config.estimate(1_000_000, 1_000_000, "claude-3-5-haiku-20241022");

        // Haiku ($0.25 + $1.25 = $1.50) should be much cheaper than Sonnet ($3 + $15 = $18)
        assertNotEquals(sonnetCost, haikuCost);
        assertEquals(1.5, haikuCost, 0.001);
        assertEquals(18.0, sonnetCost, 0.001);
    }
}
