package io.kairo.code.core.cost;

import java.util.OptionalDouble;

/**
 * Estimates USD cost from token counts and model pricing.
 */
public final class CostEstimator {

    private CostEstimator() {}

    /**
     * Estimate cost given separate input and output token counts.
     *
     * @return estimated USD, or empty if the model has no known pricing
     */
    public static OptionalDouble estimate(String modelName, long inputTokens, long outputTokens) {
        return ModelPricingTable.lookup(modelName)
                .map(
                        p ->
                                OptionalDouble.of(
                                        (inputTokens / 1_000_000.0) * p.inputPerMToken()
                                                + (outputTokens / 1_000_000.0)
                                                        * p.outputPerMToken()))
                .orElse(OptionalDouble.empty());
    }

    /**
     * Estimate cost from a total token count, assuming a 2:1 input-to-output ratio.
     *
     * @return estimated USD, or empty if the model has no known pricing
     */
    public static OptionalDouble estimate(String modelName, long totalTokens) {
        long input = (totalTokens * 2) / 3;
        long output = totalTokens - input;
        return estimate(modelName, input, output);
    }

    /** Format a USD amount as a human-readable string (e.g. "$0.031"). */
    public static String format(double usd) {
        if (usd < 0.001) {
            return String.format("<$0.001");
        } else if (usd < 0.01) {
            return String.format("$%.4f", usd);
        } else if (usd < 1.00) {
            return String.format("$%.3f", usd);
        } else {
            return String.format("$%.2f", usd);
        }
    }
}
