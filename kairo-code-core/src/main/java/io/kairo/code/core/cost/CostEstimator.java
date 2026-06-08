package io.kairo.code.core.cost;

/**
 * USD formatting utility for cost display.
 */
public final class CostEstimator {

    private CostEstimator() {}

    /** Format a USD amount as a human-readable string (e.g. "$0.031"). */
    public static String format(double usd) {
        if (usd <= 0) {
            return "$0.00";
        } else if (usd < 0.001) {
            return "<$0.001";
        } else if (usd < 0.01) {
            return String.format("$%.4f", usd);
        } else if (usd < 1.00) {
            return String.format("$%.3f", usd);
        } else {
            return String.format("$%.2f", usd);
        }
    }
}
