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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Model pricing configuration. Can be loaded from Spring properties
 * or configured programmatically.
 */
public class CostPricingConfig {

    private final Map<String, ModelPricing> models;

    public static final ModelPricing DEFAULT_PRICING = new ModelPricing(3.0, 15.0);

    public CostPricingConfig() {
        this(defaultPricingTable());
    }

    public CostPricingConfig(Map<String, ModelPricing> models) {
        this.models = new ConcurrentHashMap<>(models);
    }

    public double estimate(int inputTokens, int outputTokens, String model) {
        ModelPricing p = models.getOrDefault(model, DEFAULT_PRICING);
        return (inputTokens * p.inputPer1M() + outputTokens * p.outputPer1M()) / 1_000_000.0;
    }

    public void setModelPricing(String model, ModelPricing pricing) {
        models.put(model, pricing);
    }

    public record ModelPricing(double inputPer1M, double outputPer1M) {}

    private static Map<String, ModelPricing> defaultPricingTable() {
        return Map.of(
            "claude-sonnet-4-20250514", new ModelPricing(3.0, 15.0),
            "claude-3-5-haiku-20241022", new ModelPricing(0.25, 1.25),
            "gpt-4o", new ModelPricing(2.5, 10.0),
            "deepseek-v3", new ModelPricing(0.27, 1.10)
        );
    }
}
