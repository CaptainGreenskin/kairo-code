package io.kairo.code.core;

/**
 * Tuning knobs for the LLM-backed bash command classifier used as fallback when
 * {@code io.kairo.core.guardrail.policy.BashCommandClassifier#classify} returns
 * {@code UNKNOWN} (i.e. the static-regex catalogue could not place the command).
 *
 * <p>The classifier is wired into {@code DangerousCommandPolicy} inside
 * {@link CodeAgentFactory#createSession}. With {@code enabled=false} the policy stays in its
 * heuristic-only mode (zero LLM cost, identical to the no-arg ctor — used by unit tests and
 * users who want a fully offline guardrail). With {@code enabled=true} the policy consults
 * the configured model only for the {@code UNKNOWN} residual; known categories still resolve
 * synchronously.
 *
 * @param enabled       turn the LLM fallback on/off; default off because the classifier
 *                      issues a real model call and we want the user to opt-in
 * @param model         override model name; {@code null}/blank reuses {@link CodeAgentConfig#modelName}
 *                      so the fallback rides on whatever provider the agent already authenticates with
 * @param cacheSize     LRU bound for verdict cache; {@code <=0} resets to 512
 * @param timeoutMillis hard ceiling per LLM call; {@code <=0} resets to 5 000 ms — same value as
 *                      the upstream {@code LlmBashClassifier} default but explicit here for ops visibility
 */
public record LlmClassifierConfig(
        boolean enabled, String model, int cacheSize, long timeoutMillis) {

    public LlmClassifierConfig {
        if (cacheSize <= 0) cacheSize = 512;
        if (timeoutMillis <= 0) timeoutMillis = 5_000L;
    }

    /** Heuristic-only mode (no LLM call ever). Default for {@link CodeAgentConfig}. */
    public static LlmClassifierConfig disabled() {
        return new LlmClassifierConfig(false, null, 512, 5_000L);
    }

    /** Enabled with stock defaults; resolves model name from {@code CodeAgentConfig.modelName}. */
    public static LlmClassifierConfig enabledDefault() {
        return new LlmClassifierConfig(true, null, 512, 5_000L);
    }
}
