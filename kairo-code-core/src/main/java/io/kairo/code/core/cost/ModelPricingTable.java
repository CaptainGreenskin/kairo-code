package io.kairo.code.core.cost;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Static pricing table mapping model names to per-token costs (USD per million tokens).
 *
 * <p>Lookup uses prefix matching with longer prefixes taking priority (so "gpt-4o-mini" is not
 * matched by the "gpt-4o" entry).
 */
public final class ModelPricingTable {

    /** Pricing in USD per million tokens. */
    public record TokenPrice(double inputPerMToken, double outputPerMToken) {}

    // Ordered longest-first so prefix matching picks the most specific entry first.
    private static final LinkedHashMap<String, TokenPrice> TABLE = new LinkedHashMap<>();

    static {
        TABLE.put("gpt-4o-mini", new TokenPrice(0.15, 0.60));
        TABLE.put("gpt-4o", new TokenPrice(2.50, 10.00));
        TABLE.put("claude-3-7-sonnet", new TokenPrice(3.00, 15.00));
        TABLE.put("claude-3-5-sonnet", new TokenPrice(3.00, 15.00));
        TABLE.put("claude-3-5-haiku", new TokenPrice(0.80, 4.00));
        TABLE.put("claude-3-opus", new TokenPrice(15.00, 75.00));
        TABLE.put("claude-3-haiku", new TokenPrice(0.25, 1.25));
        TABLE.put("qwen-max", new TokenPrice(2.40, 9.60));
        TABLE.put("qwen-plus", new TokenPrice(0.40, 1.20));
        TABLE.put("qwen-turbo", new TokenPrice(0.05, 0.20));
    }

    private ModelPricingTable() {}

    /**
     * Look up pricing for the given model name (case-insensitive, prefix match).
     *
     * @return the price entry, or empty if no known pricing for this model
     */
    public static Optional<TokenPrice> lookup(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return Optional.empty();
        }
        String lower = modelName.toLowerCase();
        for (Map.Entry<String, TokenPrice> entry : TABLE.entrySet()) {
            if (lower.startsWith(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /** All registered model prefixes (for display/testing). */
    public static Iterable<String> knownModels() {
        return TABLE.keySet();
    }
}
